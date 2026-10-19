package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BridgedCommandsTests {
    private final SessionWorker io = mock(SessionWorker.class);
    private final ExecutorBridge bridge = mock(ExecutorBridge.class);
    private final ThreadPoolExecutor commandIo =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    private final BridgedCommands commands = new BridgedCommands(io, bridge, commandIo);
    private final WorkerClient.PreparedRequest exec = new WorkerClient.PreparedRequest(UUID.randomUUID(), null);
    private final ExecutionSession session = new ExecutionSession(
            new ExecutionSession.Key(UUID.randomUUID(), WorkspaceId.random(), "full"),
            mock(AuthPrincipal.class),
            true,
            UUID.randomUUID(),
            UUID.randomUUID());

    BridgedCommandsTests() {
        session.commit = "a".repeat(40);
    }

    @AfterEach
    void close() {
        commands.close();
        commandIo.shutdownNow();
    }

    @Test
    void aCommandThatFinishesDuringAPollReturnsWithoutWaitingForThePoll() {
        JsonNode finished = json("{\"ok\":true,\"state\":\"READY\",\"result\":{}}");
        when(io.prepare(eq(session), eq("EXEC"), any())).thenReturn(exec);
        when(io.send(eq(exec), any())).thenAnswer(call -> {
            Thread.sleep(30);
            return finished;
        });
        when(io.request(eq(session), eq("BRIDGE_POLL"), any(), any())).thenAnswer(call -> {
            Thread.sleep(400);
            return leased("{\"ok\":true,\"executionId\":\"\",\"bridgeRequest\":null}");
        });

        long started = System.nanoTime();
        JsonNode reply = commands.execute(
                session, UUID.randomUUID().toString(), "true", Duration.ofSeconds(10), new ExecutionTimings());

        assertThat(reply).isSameAs(finished);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
    }

    @Test
    void anAbandonedPollKeepsItsCommandIdentityAfterTheNextCommandStarts() {
        String previous = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        var oldPollEntered = new CountDownLatch(1);
        var nextCommandStarted = new CountDownLatch(1);
        var oldPollSent = new CountDownLatch(1);
        var firstAuthorization = new AtomicBoolean(true);
        var executions = new AtomicInteger();
        var dispatchedIdentity = new AtomicReference<String>();
        ThreadLocal<Boolean> delayed = ThreadLocal.withInitial(() -> false);
        JsonNode finished = json("{\"ok\":true,\"state\":\"READY\",\"result\":{}}");
        when(io.prepare(eq(session), eq("EXEC"), any())).thenReturn(exec);
        doAnswer(call -> {
                    if (firstAuthorization.compareAndSet(true, false)) {
                        delayed.set(true);
                        oldPollEntered.countDown();
                        assertThat(nextCommandStarted.await(5, TimeUnit.SECONDS))
                                .isTrue();
                    }
                    return null;
                })
                .when(io)
                .authorize(session);
        when(io.send(eq(exec), any())).thenAnswer(call -> {
            if (executions.incrementAndGet() == 1) {
                assertThat(oldPollEntered.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                nextCommandStarted.countDown();
                assertThat(oldPollSent.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return finished;
        });
        when(io.request(eq(session), eq("BRIDGE_POLL"), any(), any())).thenAnswer(call -> {
            if (delayed.get()) {
                WorkerRequests.Data data = call.getArgument(2);
                dispatchedIdentity.set(JsonMapper.shared()
                        .valueToTree(data)
                        .path("executionId")
                        .asString(""));
                oldPollSent.countDown();
            }
            return leased("{\"ok\":true,\"bridgeRequest\":null}");
        });

        assertThat(commands.execute(session, previous, "true", Duration.ofSeconds(10), new ExecutionTimings()))
                .isSameAs(finished);
        assertThat(commands.execute(session, next, "true", Duration.ofSeconds(10), new ExecutionTimings()))
                .isSameAs(finished);

        assertThat(dispatchedIdentity.get()).isEqualTo(previous);
    }

    @Test
    void aBridgeRequestRaisedWhileTheCommandRunsIsStillAnswered() throws Exception {
        String executionId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        var answered = new CountDownLatch(1);
        JsonNode finished = json("{\"ok\":true,\"state\":\"READY\",\"result\":{}}");
        when(io.prepare(eq(session), eq("EXEC"), any())).thenReturn(exec);
        when(io.send(eq(exec), any())).thenAnswer(call -> {
            assertThat(answered.await(5, TimeUnit.SECONDS)).isTrue();
            return finished;
        });
        when(io.request(eq(session), eq("BRIDGE_POLL"), any(), any()))
                .thenReturn(leased("{\"ok\":true,\"executionId\":\"" + executionId
                        + "\",\"bridgeRequest\":{\"requestId\":\"" + requestId + "\"}}"))
                .thenAnswer(call -> {
                    Thread.sleep(50);
                    return leased("{\"ok\":true,\"executionId\":\"\",\"bridgeRequest\":null}");
                });
        when(bridge.bridgeReply(eq(session), eq(executionId), any()))
                .thenReturn(new BridgeReplies.Reply(true, null, null, null, null, null, null, null));
        when(io.request(eq(session), eq("BRIDGE_COMPLETE"), any(), any())).thenAnswer(call -> {
            answered.countDown();
            return leased("{\"ok\":true}");
        });
        var timings = new ExecutionTimings();

        JsonNode reply = commands.execute(session, executionId, "poketto status", Duration.ofSeconds(10), timings);

        assertThat(reply).isSameAs(finished);
        verify(bridge).bridgeReply(eq(session), eq(executionId), any());
    }

    /** A worker reply for this lease, which the executor requires before reading it. */
    private JsonNode leased(String text) {
        ObjectNode reply = (ObjectNode) json(text);
        return reply.put("leaseId", session.leaseId.toString());
    }

    private static JsonNode json(String text) {
        return JsonMapper.shared().readTree(text);
    }
}
