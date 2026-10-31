package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BridgeAnswerEventsTests {
    @TempDir
    Path directory;

    @Test
    void statusResolutionNestsInsideTheAnswerWithoutRecordingRequestIdentity() throws Exception {
        for (boolean fullRead : List.of(true, false)) {
            try (var fixture = new Fixture(fullRead)) {
                List<RecordedEvent> events = record(fixture::execute);
                RecordedEvent answer = event(events, "poketto.WorkerBridgeAnswer");
                RecordedEvent status = event(events, "poketto.WorkerRemoteStatus");
                assertThat(answer.getBoolean("completionAttempted")).isTrue();
                assertThat(answer.getBoolean("completed")).isTrue();
                assertThat(answer.getLong("authorizationNanos")).isPositive();
                assertThat(answer.getLong("completionNanos")).isPositive();
                assertThat(status.getBoolean("fullRead")).isEqualTo(fullRead);
                assertThat(status.getBoolean("resolved")).isTrue();
                assertThat(status.getStartTime()).isAfterOrEqualTo(answer.getStartTime());
                assertThat(status.getEndTime()).isBeforeOrEqualTo(answer.getEndTime());
                assertThat(fixture.authorizations).hasValue(2);
                if (!fullRead) {
                    verify(fixture.saves, never()).currentCommit(any(), any());
                }
                for (RecordedEvent value : events) {
                    assertPrivate(value, fixture);
                }
            }
        }
    }

    @Test
    void revocationAfterStatusResolutionRecordsNoCompletionExchange() throws Exception {
        try (var fixture = new Fixture(true)) {
            var denied = new SecurityException("revoked fixture grant");
            doAnswer(call -> {
                        if (Thread.currentThread().threadId() == fixture.caller
                                && fixture.authorizations.incrementAndGet() == 2) {
                            throw denied;
                        }
                        return null;
                    })
                    .when(fixture.io)
                    .authorize(fixture.session);
            List<RecordedEvent> events =
                    record(() -> assertThatThrownBy(fixture::execute).isSameAs(denied));
            RecordedEvent answer = event(events, "poketto.WorkerBridgeAnswer");
            assertThat(answer.getLong("authorizationNanos")).isPositive();
            assertThat(answer.getLong("completionNanos")).isZero();
            assertThat(answer.getBoolean("completionAttempted")).isFalse();
            assertThat(answer.getBoolean("completed")).isFalse();
            assertThat(event(events, "poketto.WorkerRemoteStatus").getBoolean("resolved"))
                    .isTrue();
            verify(fixture.io, never()).request(eq(fixture.session), eq("BRIDGE_COMPLETE"), any(), any());
        }
    }

    @Test
    void aLostCompletionReplyKeepsItsFailureAndRecordsTheAttempt() throws Exception {
        try (var fixture = new Fixture(true)) {
            var lost = new WorkerUnavailableException();
            when(fixture.io.request(eq(fixture.session), eq("BRIDGE_COMPLETE"), any(), any()))
                    .thenThrow(lost);
            List<RecordedEvent> events =
                    record(() -> assertThatThrownBy(fixture::execute).isSameAs(lost));
            RecordedEvent answer = event(events, "poketto.WorkerBridgeAnswer");
            assertThat(answer.getBoolean("completionAttempted")).isTrue();
            assertThat(answer.getBoolean("completed")).isFalse();
            assertThat(answer.getLong("completionNanos")).isPositive();
        }
    }

    @Test
    void remoteFailureRemainsDistinctFromACompletedUnavailableStatusReply() throws Exception {
        try (var fixture = new Fixture(true)) {
            when(fixture.saves.currentCommit(any(), any()))
                    .thenThrow(new ContentRepositoryException("fixture fetch failed"));
            List<RecordedEvent> events = record(fixture::execute);
            assertThat(event(events, "poketto.WorkerBridgeAnswer").getBoolean("completed"))
                    .isTrue();
            assertThat(event(events, "poketto.WorkerRemoteStatus").getBoolean("resolved"))
                    .isFalse();
        }
    }

    private List<RecordedEvent> record(Runnable action) throws Exception {
        long caller = Thread.currentThread().threadId();
        Path file = directory.resolve("answers.jfr");
        try (var recording = new Recording()) {
            recording.enable(WorkerRequestEvents.BridgeAnswer.class);
            recording.enable(WorkerRequestEvents.RemoteStatus.class);
            recording.start();
            action.run();
            recording.stop();
            recording.dump(file);
        }
        return RecordingFile.readAllEvents(file).stream()
                .filter(value -> value.getEventType().getName().equals("poketto.WorkerBridgeAnswer")
                        || value.getEventType().getName().equals("poketto.WorkerRemoteStatus"))
                .filter(value -> value.getThread().getJavaThreadId() == caller)
                .toList();
    }

    private static RecordedEvent event(List<RecordedEvent> events, String name) {
        List<RecordedEvent> matching = events.stream()
                .filter(event -> event.getEventType().getName().equals(name))
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static void assertPrivate(RecordedEvent event, Fixture fixture) {
        assertThat(event.getStackTrace()).isNull();
        assertThat(event.toString())
                .doesNotContain(
                        "private-command-marker",
                        fixture.execution,
                        fixture.request,
                        fixture.session.key.account().toString(),
                        fixture.session.key.workspace().toString(),
                        fixture.session.commit);
        Set<String> builtins = Set.of("startTime", "duration", "eventThread", "stackTrace");
        assertThat(event.getEventType().getFields().stream()
                        .filter(field -> !builtins.contains(field.getName()))
                        .map(field -> field.getTypeName())
                        .toList())
                .allMatch(type -> type.equals("long") || type.equals("boolean"));
    }

    private static final class Fixture implements AutoCloseable {
        private final SessionWorker io = mock(SessionWorker.class);
        private final SelectedFileSaves saves = mock(SelectedFileSaves.class);
        private final ExecutorService commandIo = Executors.newSingleThreadExecutor();
        private final ExecutorBridge bridge = new ExecutorBridge(io, null, null, null, saves, null);
        private final BridgedCommands commands = new BridgedCommands(io, bridge, commandIo);
        private final ExecutionSession session;
        private final long caller = Thread.currentThread().threadId();
        private final AtomicInteger authorizations = new AtomicInteger();
        private final String execution = UUID.randomUUID().toString();
        private final String request = UUID.randomUUID().toString();

        Fixture(boolean fullRead) {
            session = new ExecutionSession(
                    new ExecutionSession.Key(UUID.randomUUID(), WorkspaceId.random(), "full"),
                    mock(AuthPrincipal.class),
                    fullRead,
                    UUID.randomUUID(),
                    UUID.randomUUID());
            session.commit = "a".repeat(40);
            session.gitCommit = session.commit;
            session.saveState = new SelectedFileSaves.State(session.commit);
            var exec = new WorkerClient.PreparedRequest(UUID.randomUUID(), null);
            var answered = new CountDownLatch(1);
            var offered = new AtomicBoolean();
            when(io.prepare(eq(session), eq("EXEC"), any())).thenReturn(exec);
            when(io.send(eq(exec), any())).thenAnswer(call -> {
                assertThat(answered.await(5, TimeUnit.SECONDS)).isTrue();
                return json("{\"ok\":true,\"state\":\"READY\",\"result\":{}}");
            });
            when(io.request(eq(session), eq("BRIDGE_POLL"), any(), any()))
                    .thenAnswer(call -> offered.compareAndSet(false, true)
                            ? leased("{\"ok\":true,\"executionId\":\"" + execution
                                    + "\",\"bridgeRequest\":{\"requestId\":\"" + request
                                    + "\",\"operation\":\"status\",\"arguments\":{}}}")
                            : leased("{\"ok\":true,\"bridgeRequest\":null}"));
            when(saves.currentCommit(any(), any())).thenReturn(Optional.of(session.commit));
            when(io.request(eq(session), eq("BRIDGE_COMPLETE"), any(), any())).thenAnswer(call -> {
                answered.countDown();
                return leased("{\"ok\":true}");
            });
            doAnswer(call -> {
                        if (Thread.currentThread().threadId() == caller) {
                            authorizations.incrementAndGet();
                        }
                        return null;
                    })
                    .when(io)
                    .authorize(session);
        }

        void execute() {
            commands.execute(
                    session, execution, "private-command-marker", Duration.ofSeconds(10), new ExecutionTimings());
        }

        private JsonNode leased(String value) {
            return ((ObjectNode) json(value)).put("leaseId", session.leaseId.toString());
        }

        private static JsonNode json(String value) {
            return JsonMapper.shared().readTree(value);
        }

        @Override
        public void close() {
            commands.close();
            commandIo.shutdownNow();
        }
    }
}
