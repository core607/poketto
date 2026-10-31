package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReadEventsTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final Set<Long> threads = ConcurrentHashMap.newKeySet();

    @Test
    void fetchAndReuseAreDistinctAndResolutionEndsBeforeTheReaderCallback() throws Exception {
        var fixture = fixture(new JGitRemoteGitTransport());
        String commit = fixture.remoteHead(workspace).name();
        var callbackEnd = new AtomicReference<Instant>();
        List<RecordedEvent> events = record(() -> {
            threads.add(Thread.currentThread().threadId());
            String resolved = fixture.authority().readObjects(workspace, snapshot -> {
                pause(80);
                callbackEnd.set(Instant.now());
                return snapshot.commitId().orElseThrow();
            });
            assertThat(resolved).isEqualTo(commit);
            assertThat(head(fixture, Optional.empty())).isEqualTo(commit);
            assertThat(head(fixture, Optional.of(commit))).isEqualTo(commit);
        });
        assertThat(events).hasSize(3);
        RecordedEvent fetched = events.getFirst();
        assertThat(fetched.getBoolean("fetchAttempted")).isTrue();
        assertThat(fetched.getBoolean("cacheReused")).isFalse();
        assertThat(fetched.getBoolean("resolved")).isTrue();
        assertThat(fetched.getLong("fetchNanos")).isPositive();
        assertThat(fetched.getEndTime()).isBefore(callbackEnd.get().minusMillis(40));
        for (RecordedEvent reused : events.subList(1, 3)) {
            assertThat(reused.getBoolean("fetchAttempted")).isFalse();
            assertThat(reused.getBoolean("cacheReused")).isTrue();
            assertThat(reused.getBoolean("resolved")).isTrue();
            assertThat(reused.getLong("fetchNanos")).isZero();
        }
        for (RecordedEvent event : events) {
            assertPrivate(event, commit);
        }
    }

    @Test
    void aReaderWaitingForTheWorkspaceLockReportsWaitSeparatelyFromFetch() throws Exception {
        var fixture = fixture(new JGitRemoteGitTransport());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiterThread = new AtomicReference<Thread>();
        List<RecordedEvent> events = record(() -> {
            var holder = new FutureTask<String>(() -> {
                threads.add(Thread.currentThread().threadId());
                return fixture.authority().readObjects(workspace, snapshot -> {
                    entered.countDown();
                    await(release);
                    return snapshot.commitId().orElseThrow();
                });
            });
            Thread.ofPlatform().name("repository-trace-holder").start(holder);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var waiter = new FutureTask<String>(() -> head(fixture, Optional.empty()));
                Thread thread =
                        Thread.ofPlatform().name("repository-trace-waiter").start(waiter);
                waiterThread.set(thread);
                waitForLock(thread);
                pause(40);
                release.countDown();
                assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo(holder.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                holder.get(5, TimeUnit.SECONDS);
            }
        });
        RecordedEvent waited = events.stream()
                .filter(event -> event.getThread().getJavaThreadId()
                        == waiterThread.get().threadId())
                .findFirst()
                .orElseThrow();
        assertThat(waited.getLong("lockWaitNanos")).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(20));
        assertThat(waited.getBoolean("cacheReused")).isTrue();
        assertThat(waited.getBoolean("fetchAttempted")).isFalse();
        assertThat(waited.getLong("fetchNanos")).isZero();
    }

    @Test
    void failedFetchIsRecordedWithoutClaimingResolutionAndTheNextReadCanRecover() throws Exception {
        RemoteGitTransport transport = mock(RemoteGitTransport.class);
        when(transport.fetchMain(any(), any())).thenThrow(new RemoteGitTransportException("fetch"));
        var fixture = fixture(transport);
        List<RecordedEvent> events = record(() -> {
            assertThatThrownBy(() -> head(fixture, Optional.empty())).isInstanceOf(ContentRepositoryException.class);
            var delegate = new JGitRemoteGitTransport();
            doAnswer(call -> delegate.fetchMain(call.getArgument(0), call.getArgument(1)))
                    .when(transport)
                    .fetchMain(any(), any());
            assertThat(head(fixture, Optional.empty()))
                    .isEqualTo(fixture.remoteHead(workspace).name());
        });
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().getBoolean("fetchAttempted")).isTrue();
        assertThat(events.getFirst().getLong("fetchNanos")).isPositive();
        assertThat(events.getFirst().getBoolean("cacheReused")).isFalse();
        assertThat(events.getFirst().getBoolean("resolved")).isFalse();
        assertThat(events.getLast().getBoolean("resolved")).isTrue();
    }

    private RemoteRepositoryFixture fixture(RemoteGitTransport transport) throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport, Duration.ofMinutes(5));
        fixture.commitRemote(
                workspace, Map.of("private/trace-marker.md", "fixture-content".getBytes(StandardCharsets.UTF_8)));
        return fixture;
    }

    private String head(RemoteRepositoryFixture fixture, Optional<String> known) {
        threads.add(Thread.currentThread().threadId());
        return fixture.authority()
                .readObjects(workspace, known, snapshot -> snapshot.commitId().orElseThrow());
    }

    private List<RecordedEvent> record(CheckedAction action) throws Exception {
        Path file = directory.resolve("reads.jfr");
        try (var recording = new Recording()) {
            recording.enable(RepositoryReadEvent.class);
            recording.start();
            action.run();
            recording.stop();
            recording.dump(file);
        }
        return RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("poketto.RepositoryReadResolution"))
                .filter(event -> threads.contains(event.getThread().getJavaThreadId()))
                .sorted((left, right) -> left.getStartTime().compareTo(right.getStartTime()))
                .toList();
    }

    private void assertPrivate(RecordedEvent event, String commit) {
        assertThat(event.getStackTrace()).isNull();
        assertThat(event.toString())
                .doesNotContain(
                        workspace.toString(),
                        directory.toString(),
                        commit,
                        "trace-marker",
                        "fixture-content",
                        "credentials");
        Set<String> builtins = Set.of("startTime", "duration", "eventThread", "stackTrace");
        assertThat(event.getEventType().getFields().stream()
                        .filter(field -> !builtins.contains(field.getName()))
                        .map(field -> field.getTypeName())
                        .toList())
                .allMatch(type -> type.equals("long") || type.equals("boolean"));
    }

    private static void waitForLock(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while separating tracing phases", interrupted);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the fixture reader", interrupted);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
