package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;

/**
 * Runs one EXEC while answering the bridge requests its command raises.
 *
 * <p>A bridge poll waits up to the worker's poll interval for a frame, and that wait cannot be cut
 * short from here. Polls therefore run on their own threads while the caller watches both the poll
 * and the command, so a command that finishes during a poll is returned at once instead of after
 * the poll times out. An abandoned poll ends on its own within that interval; the command's reply
 * is final, exactly as when the loop ended after a poll before.
 */
final class BridgedCommands implements AutoCloseable {
    private static final long WATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private final SessionWorker io;
    private final ExecutorBridge bridge;
    private final ThreadPoolExecutor commandIo;
    private final ExecutorService polls = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("poketto-bridge-poll-", 0).factory());

    BridgedCommands(SessionWorker io, ExecutorBridge bridge, ThreadPoolExecutor commandIo) {
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
        var running = commandIo.submit(() -> io.request(
                session,
                "EXEC",
                new WorkerRequests.Exec(executionId, session.commit, command, timeout.toMillis()),
                timeout.plusSeconds(5)));
        long deadline = System.nanoTime() + timeout.plusSeconds(5).toNanos();
        try {
            while (!running.isDone() && !session.stopping.get()) {
                Optional<JsonNode> polled = awaitPoll(polls.submit(() -> pollBridge(session, deadline)), running);
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
            return running.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
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
    private static Optional<JsonNode> awaitPoll(Future<Optional<JsonNode>> poll, Future<JsonNode> running)
            throws InterruptedException, ExecutionException {
        while (true) {
            try {
                return poll.get(WATCH_NANOS, TimeUnit.NANOSECONDS);
            } catch (TimeoutException waiting) {
                if (running.isDone()) {
                    return Optional.empty();
                }
            }
        }
    }

    // One poll of the worker's bridge mailbox. A poll that fails while the session is stopping
    // ends the loop and leaves the command's own reply to decide the outcome.
    private Optional<JsonNode> pollBridge(ExecutionSession session, long deadline) {
        io.authorize(session);
        if (System.nanoTime() - deadline >= 0) {
            throw new WorkerUnavailableException();
        }
        try {
            return Optional.of(
                    io.request(session, "BRIDGE_POLL", new WorkerRequests.BridgePoll(), Duration.ofSeconds(3)));
        } catch (RuntimeException failed) {
            if (session.stopping.get()) {
                return Optional.empty();
            }
            throw failed;
        }
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
}
