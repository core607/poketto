package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BridgePollEventsTests {
    @TempDir
    Path root;

    private final SessionWorker io = mock(SessionWorker.class);
    private final ExecutorBridge bridge = mock(ExecutorBridge.class);
    private final ExecutorService commandIo = Executors.newSingleThreadExecutor();
    private final BridgedCommands commands = new BridgedCommands(io, bridge, commandIo);
    private final WorkerClient.PreparedRequest exec = new WorkerClient.PreparedRequest(UUID.randomUUID(), null);
    private final ExecutionSession session = new ExecutionSession(
            new ExecutionSession.Key(UUID.randomUUID(), WorkspaceId.random(), "full"),
            mock(AuthPrincipal.class),
            true,
            UUID.randomUUID(),
            UUID.randomUUID());
    private final String executionId = UUID.randomUUID().toString();
    private final Set<Long> observedThreads = ConcurrentHashMap.newKeySet();

    BridgePollEventsTests() {
        session.commit = "a".repeat(40);
        when(io.prepare(eq(session), eq("EXEC"), any())).thenReturn(exec);
    }

    @AfterEach
    void close() {
        commands.close();
        commandIo.shutdownNow();
    }

    @Test
    void emptyRepliesAndActualBridgeRequestsHaveDistinctEvents() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var answered = new CountDownLatch(1);
        var polls = new AtomicInteger();
        when(io.send(eq(exec), any())).thenAnswer(call -> {
            assertThat(answered.await(5, TimeUnit.SECONDS)).isTrue();
            return json("{\"ok\":true,\"state\":\"READY\",\"result\":{}}");
        });
        when(io.request(eq(session), eq("BRIDGE_POLL"), any(), any())).thenAnswer(call -> {
            int number = polls.incrementAndGet();
            if (number <= 2) {
                observedThreads.add(Thread.currentThread().threadId());
            }
            return number == 2
                    ? leased("{\"ok\":true,\"executionId\":\"" + executionId + "\",\"bridgeRequest\":{\"requestId\":\""
                            + requestId + "\"}}")
                    : leased("{\"ok\":true,\"bridgeRequest\":null}");
        });
        when(bridge.bridgeReply(eq(session), eq(executionId), any()))
                .thenReturn(new BridgeReplies.Reply(true, null, null, null, null, null, null, null));
        when(io.request(eq(session), eq("BRIDGE_COMPLETE"), any(), any())).thenAnswer(call -> {
            answered.countDown();
            return leased("{\"ok\":true}");
        });

        List<RecordedEvent> events = record(this::execute);

        assertThat(events).hasSize(2);
        assertThat(events)
                .extracting(event -> event.getBoolean("requestPresent"))
                .containsExactlyInAnyOrder(false, true);
        for (RecordedEvent event : events) {
            assertThat(event.getBoolean("receivedReply")).isTrue();
            assertThat(event.getBoolean("replyOk")).isTrue();
            assertThat(event.getLong("authorizationNanos")).isPositive();
            assertThat(event.getLong("requestNanos")).isPositive();
            assertThat(event.getStackTrace()).isNull();
            assertThat(event.toString()).doesNotContain("private-command-marker", executionId, requestId);
        }
    }

    @Test
    void aFailedExchangeDoesNotCountAsAnEmptySuccessfulReply() throws Exception {
        holdCommandUntilCancelled();
        var failure = new WorkerUnavailableException();
        when(io.request(eq(session), eq("BRIDGE_POLL"), any(), any())).thenAnswer(call -> {
            observedThreads.add(Thread.currentThread().threadId());
            throw failure;
        });

        List<RecordedEvent> events =
                record(() -> assertThatThrownBy(this::execute).isSameAs(failure));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getBoolean("receivedReply")).isFalse();
            assertThat(event.getBoolean("replyOk")).isFalse();
            assertThat(event.getBoolean("requestPresent")).isFalse();
            assertThat(event.getLong("authorizationNanos")).isPositive();
            assertThat(event.getLong("requestNanos")).isPositive();
        });
    }

    @Test
    void aFailedAuthorizationRecordsNoRequestTime() throws Exception {
        holdCommandUntilCancelled();
        var failure = new SecurityException("test authorization revoked");
        doAnswer(call -> {
                    observedThreads.add(Thread.currentThread().threadId());
                    throw failure;
                })
                .when(io)
                .authorize(session);

        List<RecordedEvent> events =
                record(() -> assertThatThrownBy(this::execute).isSameAs(failure));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getBoolean("receivedReply")).isFalse();
            assertThat(event.getLong("authorizationNanos")).isPositive();
            assertThat(event.getLong("requestNanos")).isZero();
        });
    }

    private List<RecordedEvent> record(Runnable action) throws Exception {
        Path file = root.resolve("polls.jfr");
        try (var recording = new Recording()) {
            recording.enable(WorkerRequestEvents.BridgePoll.class);
            recording.start();
            action.run();
            recording.stop();
            recording.dump(file);
        }
        return RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("poketto.WorkerBridgePoll"))
                .filter(event -> observedThreads.contains(event.getThread().getJavaThreadId()))
                .toList();
    }

    private void execute() {
        commands.execute(
                session, executionId, "private-command-marker", Duration.ofSeconds(10), new ExecutionTimings());
    }

    private void holdCommandUntilCancelled() {
        when(io.send(eq(exec), any())).thenAnswer(call -> {
            new CountDownLatch(1).await();
            throw new AssertionError("Only interruption should end this command");
        });
    }

    private JsonNode leased(String text) {
        ObjectNode reply = (ObjectNode) json(text);
        return reply.put("leaseId", session.leaseId.toString());
    }

    private static JsonNode json(String text) {
        return JsonMapper.shared().readTree(text);
    }
}
