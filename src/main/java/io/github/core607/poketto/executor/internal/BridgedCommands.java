package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;

/**
 * Runs one EXEC while answering the bridge requests its command raises.
 *
 * <p>A bridge poll waits up to the worker's poll interval for a frame, and that wait cannot be cut
 * short from here. Polls therefore run on their own threads while the caller blocks until either
 * the poll or the command completes, so a command that finishes during a poll is returned at once
 * instead of after the poll times out, and nothing spins meanwhile. Each poll names its command,
 * so an abandoned poll cannot consume the next command's requests even if it reaches the worker
 * after that command starts.
 */
final class BridgedCommands implements AutoCloseable {
    private final SessionWorker io;
    private final ExecutorBridge bridge;
    private final ExecutorService commandIo;
    private final ExecutorService polls = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("poketto-bridge-poll-", 0).factory());

    BridgedCommands(SessionWorker io, ExecutorBridge bridge, ExecutorService commandIo) {
        this.io = io;
        this.bridge = bridge;
        this.commandIo = commandIo;
    }

    // The EXEC request runs on its own thread while this one serves the worker's bridge requests
    // until the command finishes, the session stops, or the worker reports the lease closed.
    JsonNode execute(
            ExecutionSession session, String executionId, String command, Duration timeout, ExecutionTimings timings) {
        // Taken before the worker starts its own clock, so host work that honours it ends earlier.
        session.commandDeadline = System.nanoTime() + timeout.toNanos();
        WorkerClient.PreparedRequest exec;
        try {
            exec = io.prepare(
                    session, "EXEC", new WorkerRequests.Exec(executionId, session.commit, command, timeout.toMillis()));
        } catch (WorkerUnavailableException stopped) {
            throw new CommandNotRunException(
                    ExecutionAdmissionException.Reason.UNAVAILABLE,
                    "The lease stopped before the command was sent",
                    stopped);
        }
        var finished = new CompletableFuture<Void>();
        var running = commandIo.submit(() -> {
            try {
                return io.send(exec, timeout.plusSeconds(5));
            } finally {
                finished.complete(null);
            }
        });
        long deadline = System.nanoTime() + timeout.plusSeconds(5).toNanos();
        try {
            while (!running.isDone() && !session.stopping.get()) {
                Optional<JsonNode> polled = awaitPoll(session, executionId, deadline, finished);
                if (polled.isEmpty() || running.isDone() || session.stopping.get() || closedByWorker(polled.get())) {
                    break;
                }
                requireOk(polled.get(), session);
                JsonNode request = polled.get().path("bridgeRequest");
                if (!request.isMissingNode() && !request.isNull()) {
                    long answering = System.nanoTime();
                    answerBridgeRequest(session, executionId, polled.get(), request);
                    timings.bridged(System.nanoTime() - answering);
                }
            }
            // The EXEC reply owns its terminal result; closing the mailbox during cancellation
            // must not turn a confirmed cancelled command into an unknown transport outcome.
            JsonNode response = running.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            timings.worker(response.path("result"));
            if (refusedBeforeRunning(response) instanceof ExecutionAdmissionException.Reason refusal) {
                throw new CommandNotRunException(
                        refusal,
                        "The worker refused the command: "
                                + response.path("code").asString(""),
                        null);
            }
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new WorkerUnavailableException();
        } catch (TimeoutException expired) {
            throw new WorkerUnavailableException(expired);
        } finally {
            running.cancel(true);
        }
    }

    /** The poll's result, or empty once the command finished while the poll was still waiting. */
    private Optional<JsonNode> awaitPoll(
            ExecutionSession session, String executionId, long deadline, CompletableFuture<Void> finished)
            throws InterruptedException, ExecutionException {
        var poll = new CompletableFuture<Optional<JsonNode>>();
        polls.execute(() -> {
            try {
                poll.complete(pollBridge(session, executionId, deadline));
            } catch (Throwable failure) {
                poll.completeExceptionally(failure);
            }
        });
        CompletableFuture.anyOf(poll, finished).get();
        return poll.isDone() ? poll.get() : Optional.empty();
    }

    // One poll of the worker's bridge mailbox. A poll that fails while the session is stopping
    // ends the loop and leaves the command's own reply to decide the outcome.
    private Optional<JsonNode> pollBridge(ExecutionSession session, String executionId, long deadline) {
        var event = new WorkerRequestEvents.BridgePoll();
        event.begin();
        try {
            long authorizing = System.nanoTime();
            try {
                io.authorize(session);
            } finally {
                event.authorizationNanos = System.nanoTime() - authorizing;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new WorkerUnavailableException();
            }
            long requesting = System.nanoTime();
            try {
                JsonNode reply = io.request(
                        session, "BRIDGE_POLL", new WorkerRequests.BridgePoll(executionId), Duration.ofSeconds(3));
                event.receivedReply = true;
                event.replyOk = reply.path("ok").asBoolean(false);
                JsonNode request = reply.path("bridgeRequest");
                event.requestPresent = !request.isMissingNode() && !request.isNull();
                return Optional.of(reply);
            } catch (RuntimeException failed) {
                if (session.stopping.get()) {
                    return Optional.empty();
                }
                throw failed;
            } finally {
                event.requestNanos = System.nanoTime() - requesting;
            }
        } finally {
            event.end();
            event.commit();
        }
    }

    /**
     * A refusal the worker makes before the command runs; nothing executed, so a retry is safe. The
     * worker checks a lease's expiry before it accepts an EXEC, so an expired lease ran nothing.
     */
    private static ExecutionAdmissionException.Reason refusedBeforeRunning(JsonNode response) {
        if (WorkerResponses.refused(response, "EXECUTION_CAPACITY")) {
            return ExecutionAdmissionException.Reason.CAPACITY;
        }
        if (WorkerResponses.refused(response, "SESSION_BUSY")) {
            return ExecutionAdmissionException.Reason.BUSY;
        }
        if (WorkerResponses.refused(response, "LEASE_EXPIRED")) {
            return ExecutionAdmissionException.Reason.UNAVAILABLE;
        }
        return null;
    }

    private static boolean closedByWorker(JsonNode polled) {
        return !polled.path("ok").asBoolean(false)
                && Set.of("LEASE_EXPIRED", "BRIDGE_UNAVAILABLE", "AUTH_REVOKED")
                        .contains(polled.path("code").asString(""));
    }

    // A bridge request belongs to this execution and names a well-formed request id; the session
    // must still be live and authorized both before and after the reply is computed.
    private void answerBridgeRequest(ExecutionSession session, String executionId, JsonNode polled, JsonNode request) {
        if (!executionId.equals(polled.path("executionId").asString(""))) {
            throw new WorkerUnavailableException();
        }
        String requestId = request.path("requestId").stringValue();
        if (!UUID.fromString(requestId).toString().equals(requestId)) {
            throw new WorkerUnavailableException();
        }
        requireLive(session);
        io.authorize(session);
        var reply = bridge.bridgeReply(session, executionId, request);
        requireLive(session);
        io.authorize(session);
        requireOk(
                io.request(
                        session,
                        "BRIDGE_COMPLETE",
                        new WorkerRequests.BridgeComplete(executionId, requestId, reply),
                        Duration.ofSeconds(3)),
                session);
    }

    @Override
    public void close() {
        polls.shutdownNow();
    }

    /**
     * The command never ran: the worker refused it before running, or the lease stopped before its
     * EXEC was signed, so nothing reached the worker. The message names which, for the log.
     */
    static final class CommandNotRunException extends IllegalStateException {
        private final ExecutionAdmissionException.Reason reason;

        CommandNotRunException(ExecutionAdmissionException.Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        ExecutionAdmissionException.Reason reason() {
            return reason;
        }
    }
}
