package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

class NativeRepositoryPhaseTests extends NativeRepositoryTestFixture {
    @Test
    void recordingPreservesParentReuseAndKeepsProductIdentitiesOutOfEvents() throws IOException {
        Path output = root.resolve("phases.jfr");
        try (var recording = recording();
                var executor = executor((run, timeout, handler) -> {})) {
            recording.start();
            String copy = execute(executor, "new").copyId();
            assertThat(execute(executor, copy).freshSandbox()).isTrue();
            recording.stop();
            recording.dump(output);
            assertThat(journal.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            verify(client).open(any());
        }
        List<RecordedEvent> events = phases(output);
        Map<Long, List<RecordedEvent>> groups =
                events.stream().collect(Collectors.groupingBy(event -> event.getLong("invocation")));
        assertThat(groups).hasSize(2);
        assertThat(events)
                .filteredOn(event -> name(event).equals("SESSION_START"))
                .hasSize(1);
        assertThat(events)
                .filteredOn(event -> name(event).equals("SESSION_RENEW"))
                .hasSize(1);
        assertThat(events)
                .filteredOn(event -> name(event).equals("CHILD_START"))
                .hasSize(2);
        groups.values().forEach(group -> {
            RecordedEvent request = group.stream()
                    .filter(event -> name(event).equals("REQUEST"))
                    .findFirst()
                    .orElseThrow();
            assertThat(request.getInt("depth")).isZero();
            assertThat(group).allSatisfy(event -> {
                assertThat(event.getStartTime()).isAfterOrEqualTo(request.getStartTime());
                assertThat(event.getEndTime()).isBeforeOrEqualTo(request.getEndTime());
            });
        });
        assertThat(events.getFirst().getFields())
                .extracting(field -> field.getName())
                .containsExactlyInAnyOrder(
                        "startTime",
                        "duration",
                        "eventThread",
                        "stackTrace",
                        "invocation",
                        "phase",
                        "parentPhase",
                        "depth",
                        "threadCpuNanos",
                        "transferredBytes");
        assertThat(events).allSatisfy(event -> assertThat(event.getStackTrace()).isNull());
    }

    @Test
    void failedObservationIsNotReplayedAndDoesNotLeakCorrelationIntoTheNextCommand() throws IOException {
        Path output = root.resolve("failure.jfr");
        var fail = new AtomicBoolean(true);
        try (var recording = recording();
                var executor = executor((run, timeout, handler) -> {
                    if (fail.getAndSet(false)) {
                        throw new WorkerUnavailableException();
                    }
                })) {
            recording.start();
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            String copy = journal.record(owner).orElseThrow().copyId().toString();
            assertThat(execute(executor, copy).retention().lastInterruptedCommand())
                    .isNotNull();
            recording.stop();
            recording.dump(output);
        }
        List<RecordedEvent> requests = phases(output).stream()
                .filter(event -> name(event).equals("REQUEST"))
                .toList();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).getLong("invocation"))
                .isNotEqualTo(requests.get(1).getLong("invocation"));
        assertThat(requests)
                .allSatisfy(event -> assertThat(event.getInt("depth")).isZero());
        assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
    }

    @Test
    void disabledRecordingKeepsExistingExecutionAndFailureBehavior() throws IOException {
        Path output = root.resolve("disabled.jfr");
        try (var recording = new Recording();
                var executor = executor((run, timeout, handler) -> {})) {
            recording.start();
            String copy = execute(executor, "new").copyId();
            doThrow(new WorkerUnavailableException()).when(auth).authorize(any(), any(), any());
            assertThatThrownBy(() -> execute(executor, copy)).isInstanceOf(WorkerUnavailableException.class);
            recording.stop();
            recording.dump(output);
        }
        assertThat(phases(output)).isEmpty();
    }

    private static Recording recording() {
        var recording = new Recording();
        recording
                .enable(NativeRepositoryPhase.class)
                .withThreshold(Duration.ZERO)
                .withoutStackTrace();
        recording.setMaxSize(4L << 20);
        return recording;
    }

    private static List<RecordedEvent> phases(Path file) throws IOException {
        return RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals(NativeRepositoryPhase.EVENT_NAME))
                .toList();
    }

    private static String name(RecordedEvent event) {
        return event.getString("phase");
    }
}
