package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class ImmutableRepositoryReadTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final WorkspaceId other = WorkspaceId.random();
    private final byte[] image = "exact image payload".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void canonicalStorageRoot() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    void blockedExactBlobReadDoesNotDelayPublicationWithdrawal() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(workspace, files());
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
        assertThat(snapshots.refresh(workspace).articles()).hasSize(1);
        var descriptor = new JGitRepositoryBlobReader(fixture.authority())
                .find(workspace, commit.name(), "image.png")
                .orElseThrow();
        var gate = new PayloadGate(fixture.authority(), descriptor);
        fixture.commitRemote(workspace, Map.of("private/withdrawn.md", text("# Withdrawn")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reading = pool.submit(() -> gate.blobs.read(descriptor));
            try {
                await(gate.entered);
                var withdrawn = pool.submit(() -> snapshots.refresh(workspace)).get(5, TimeUnit.SECONDS);
                assertThat(withdrawn.articles()).isEmpty();
                assertThat(snapshots.current(workspace).articles()).isEmpty();
                assertThat(reading).isNotDone();
            } finally {
                gate.release.countDown();
            }
            assertThat(reading.get(5, TimeUnit.SECONDS)).isEqualTo(image);
        }
    }

    @Test
    void activeExactReadPreventsEvictionUntilItsCallbackAndHandlesFinish() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 1);
        var descriptor = load(fixture);
        var gate = new PayloadGate(fixture.authority(), descriptor);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reading = pool.submit(() -> gate.blobs.read(descriptor));
            try {
                await(gate.entered);
                assertCapacityOccupied(fixture);
                assertThat(fixture.cache(workspace)).isDirectory();
            } finally {
                gate.release.countDown();
            }
            assertThat(reading.get(5, TimeUnit.SECONDS)).isEqualTo(image);
            fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
            assertThat(fixture.cache(workspace)).doesNotExist();
        }
    }

    @Test
    void unbornRefreshRemovesMainAndWorktreeWhileAnOldExactReadRemainsUsable() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var descriptor = load(fixture);
        fixture.authority().read(workspace, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace).resolve("image.png")).exists();
        var gate = new PayloadGate(fixture.authority(), descriptor);
        try (var remote = fixture.openRemote(workspace)) {
            var update = remote.updateRef(Constants.R_HEADS + "main");
            update.setForceUpdate(true);
            update.delete();
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reading = pool.submit(() -> gate.blobs.read(descriptor));
            try {
                await(gate.entered);
                var empty = pool.submit(
                                () -> fixture.authority().readObjects(workspace, snapshot -> snapshot.commitId()))
                        .get(5, TimeUnit.SECONDS);
                assertThat(empty).isEmpty();
                assertThat(fixture.cache(workspace).resolve("image.png")).doesNotExist();
                try (var cache = JGitContentRepositoryStore.openCache(fixture.cache(workspace), workspace)) {
                    assertThat(cache.resolve(Constants.R_HEADS + "main")).isNull();
                    assertThat(cache.readDirCache().getEntryCount()).isZero();
                }
            } finally {
                gate.release.countDown();
            }
            assertThat(reading.get(5, TimeUnit.SECONDS)).isEqualTo(image);
        }
        assertThat(new JGitRepositoryBlobReader(fixture.authority()).read(descriptor))
                .isEqualTo(image);
        var replacement = fixture.commitRemote(workspace, Map.of("new.md", text("# New root")));
        fixture.authority().readObjects(workspace, snapshot -> snapshot.commitId());
        assertThat(replacement.name()).isNotEqualTo(descriptor.commit());
        assertThat(new JGitRepositoryBlobReader(fixture.authority()).read(descriptor))
                .isEqualTo(image);
    }

    @Test
    void checkedAndUncheckedCallbackFailuresReleaseTheCachePin() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 1);
        load(fixture);
        assertThatThrownBy(() -> fixture.authority().readImmutableObjects(workspace, objects -> {
                    throw new IOException("injected reader failure");
                }))
                .isInstanceOf(ContentRepositoryException.class)
                .hasCauseInstanceOf(IOException.class);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
        load(fixture);
        assertThatThrownBy(() -> fixture.authority().readImmutableObjects(workspace, objects -> {
                    throw new IllegalStateException("injected callback failure");
                }))
                .isInstanceOf(IllegalStateException.class);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
    }

    @Test
    void failedOpeningDoesNotLeaveAPinOnTheMissingCache() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 1);
        assertThatThrownBy(() -> fixture.authority().readImmutableObjects(workspace, objects -> "unused"))
                .isInstanceOf(ContentRepositoryException.class);
        load(fixture);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
    }

    @Test
    void cancellationDoesNotUnpinAProducerThatHasNotActuallyExited() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 1);
        var descriptor = load(fixture);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reading = pool.submit(() -> {
                try {
                    return fixture.authority().readImmutableObjects(workspace, objects -> {
                        entered.countDown();
                        try {
                            release.await();
                            throw new AssertionError("the reader must be cancelled first");
                        } catch (InterruptedException cancelled) {
                            interrupted.countDown();
                        }
                        await(release);
                        return objects.open(ObjectId.fromString(descriptor.objectId()), Constants.OBJ_BLOB)
                                .getBytes();
                    });
                } finally {
                    exited.countDown();
                }
            });
            try {
                await(entered);
                assertThat(reading.cancel(true)).isTrue();
                await(interrupted);
                assertCapacityOccupied(fixture);
            } finally {
                release.countDown();
            }
            await(exited);
            fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
            assertThat(fixture.cache(workspace)).doesNotExist();
        }
    }

    @Test
    void immutableReadsDoNotMaterializeFilesOrReattachHead() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var descriptor = load(fixture);
        try (var cache = JGitContentRepositoryStore.openCache(fixture.cache(workspace), workspace)) {
            var detached = cache.updateRef(Constants.HEAD, true);
            detached.setNewObjectId(ObjectId.fromString(descriptor.commit()));
            detached.forceUpdate();
        }
        Path head = fixture.cache(workspace).resolve(".git/HEAD");
        String before = Files.readString(head);
        var blobs = new JGitRepositoryBlobReader(fixture.authority());
        assertThat(blobs.find(workspace, descriptor.commit(), descriptor.path()))
                .contains(descriptor);
        assertThat(blobs.images(workspace, descriptor.commit(), "")).containsExactly(descriptor);
        assertThat(blobs.siblings(workspace, descriptor.commit(), "article.md", 128, false, java.util.Set.of())
                        .items())
                .containsExactly(descriptor);
        assertThat(blobs.read(descriptor)).isEqualTo(image);
        assertThat(Files.readString(head)).isEqualTo(before);
        assertThat(fixture.cache(workspace).resolve("image.png")).doesNotExist();
    }

    @Test
    void failedOpeningDoesNotPromoteACorruptCacheInTheEvictionOrder() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 2);
        load(fixture);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        Path cache = fixture.cache(workspace);
        Files.writeString(cache.resolve(".git/config"), "[unterminated");
        FileTime older = FileTime.fromMillis(1000);
        Files.setLastModifiedTime(cache, older);
        Files.setLastModifiedTime(fixture.cache(other), FileTime.fromMillis(2000));
        assertThatThrownBy(() -> fixture.authority().readImmutableObjects(workspace, objects -> "unreachable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
        assertThat(Files.getLastModifiedTime(cache)).isEqualTo(older);
        fixture.authority().readObjects(WorkspaceId.random(), snapshot -> snapshot.commitId());
        assertThat(cache).doesNotExist();
        assertThat(fixture.cache(other)).isDirectory();
    }

    @Test
    void failedTouchClosesTheOpenedRepositoryAndPreservesAnyCloseFailure() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 1);
        load(fixture);
        Path cache = fixture.cache(workspace);
        Path moved = cache.resolveSibling("moved-cache");
        assertThat(moved.toAbsolutePath().normalize().startsWith(directory)).isTrue();
        Repository opened = spy(JGitContentRepositoryStore.openCache(cache, workspace));
        var gitDirectory = opened.getDirectory();
        var closed = new AtomicBoolean();
        var closeFailure = new IllegalStateException("injected close failure after real closure");
        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    closed.set(true);
                    throw closeFailure;
                })
                .when(opened)
                .close();
        try (var construction = mockConstruction(FileRepositoryBuilder.class, (builder, context) -> {
            when(builder.getGitDir()).thenReturn(gitDirectory);
            when(builder.build()).thenAnswer(invocation -> {
                // Model the cache directory disappearing after a successful open. The following
                // touch fails through the real filesystem; the repository handle is still real.
                Files.move(cache, moved);
                return opened;
            });
        })) {
            assertThatThrownBy(() -> fixture.authority().readImmutableObjects(workspace, objects -> "unreachable"))
                    .isInstanceOf(ContentRepositoryException.class)
                    .hasMessage("repository cache access time cannot be recorded")
                    .hasCauseInstanceOf(NoSuchFileException.class)
                    .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(closeFailure));
            verify(opened).close();
        } finally {
            if (!closed.get()) {
                doCallRealMethod().when(opened).close();
                opened.close();
            }
            if (Files.exists(moved)) Files.move(moved, cache);
        }
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(cache).doesNotExist();
    }

    @Test
    void unbornRefFailuresIdentifyDetachDeleteAndRelinkSeparately() throws Exception {
        for (String operation : new String[] {"HEAD detach", "main delete", "HEAD relink"}) {
            var fixture = new RemoteRepositoryFixture(directory.resolve(operation.replace(' ', '-')));
            load(fixture);
            try (Repository repository = JGitContentRepositoryStore.openCache(fixture.cache(workspace), workspace)) {
                Repository observed = spy(repository);
                Path headLock = repository.getDirectory().toPath().resolve("HEAD.lock");
                Path mainLock = repository.getDirectory().toPath().resolve("refs/heads/main.lock");
                if (operation.equals("HEAD detach")) Files.createFile(headLock);
                if (operation.equals("main delete")) Files.createFile(mainLock);
                if (operation.equals("HEAD relink")) failRelinkWithRealLock(observed, headLock);
                try {
                    assertThatThrownBy(() -> removeMain(observed))
                            .isInstanceOf(ContentRepositoryException.class)
                            .hasMessage("repository object cache " + operation + " failed: LOCK_FAILURE");
                } finally {
                    Files.deleteIfExists(headLock);
                    Files.deleteIfExists(mainLock);
                }
            }
        }
    }

    @Test
    void unbornCleanupFailureIsSuppressedBehindTheOriginalResultOrIoFailure() throws Exception {
        for (boolean ioFailure : new boolean[] {false, true}) {
            var fixture = new RemoteRepositoryFixture(directory.resolve("suppressed-" + ioFailure));
            load(fixture);
            try (Repository repository = JGitContentRepositoryStore.openCache(fixture.cache(workspace), workspace)) {
                Repository observed = spy(repository);
                Path headLock = repository.getDirectory().toPath().resolve("HEAD.lock");
                Path mainLock = repository.getDirectory().toPath().resolve("refs/heads/main.lock");
                var injected = new IOException("injected ref deletion I/O failure");
                if (ioFailure) {
                    var update = spy(repository.updateRef(Constants.R_HEADS + "main"));
                    doThrow(injected).when(update).delete();
                    doReturn(update).when(observed).updateRef(Constants.R_HEADS + "main");
                } else {
                    Files.createFile(mainLock);
                }
                failRelinkWithRealLock(observed, headLock);
                try {
                    Throwable failure = catchThrowable(() -> removeMain(observed));
                    assertThat(failure).isInstanceOf(ContentRepositoryException.class);
                    Throwable primary = failure;
                    if (ioFailure) {
                        assertThat(failure.getCause()).isSameAs(injected);
                        primary = failure.getCause();
                    } else {
                        assertThat(failure).hasMessage("repository object cache main delete failed: LOCK_FAILURE");
                    }
                    assertThat(primary.getSuppressed())
                            .singleElement()
                            .satisfies(suppressed -> assertThat(suppressed)
                                    .hasMessage("repository object cache HEAD relink failed: LOCK_FAILURE"));
                } finally {
                    Files.deleteIfExists(headLock);
                    Files.deleteIfExists(mainLock);
                }
            }
        }
    }

    private static void failRelinkWithRealLock(Repository repository, Path headLock) throws Exception {
        doAnswer(invocation -> {
                    Files.createFile(headLock);
                    return invocation.callRealMethod();
                })
                .when(repository)
                .updateRef(Constants.HEAD);
    }

    private static void removeMain(Repository repository) {
        ReflectionTestUtils.invokeMethod(
                JGitRemoteRepositoryAuthority.class, "updateObjectRef", repository, ObjectId.zeroId());
    }

    @Test
    void immutableReadStillChecksTheExactCommitPathObjectAndSize() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var descriptor = load(fixture);
        var blobs = new JGitRepositoryBlobReader(fixture.authority());
        assertThat(blobs.read(descriptor)).isEqualTo(image);
        for (var forged : java.util.List.of(
                new RepositoryBlob(
                        workspace, "1".repeat(40), descriptor.path(), descriptor.objectId(), descriptor.size(), true),
                new RepositoryBlob(
                        workspace, descriptor.commit(), "absent.png", descriptor.objectId(), descriptor.size(), true),
                new RepositoryBlob(
                        workspace, descriptor.commit(), descriptor.path(), "1".repeat(40), descriptor.size(), true),
                new RepositoryBlob(
                        workspace,
                        descriptor.commit(),
                        descriptor.path(),
                        descriptor.objectId(),
                        descriptor.size() + 1,
                        true))) {
            assertThatThrownBy(() -> blobs.read(forged)).isInstanceOf(ContentRepositoryException.class);
        }
    }

    private RepositoryBlob load(RemoteRepositoryFixture fixture) throws Exception {
        var commit = fixture.commitRemote(workspace, files());
        fixture.authority().readObjects(workspace, snapshot -> snapshot.commitId());
        return new JGitRepositoryBlobReader(fixture.authority())
                .find(workspace, commit.name(), "image.png")
                .orElseThrow();
    }

    private void assertCapacityOccupied(RemoteRepositoryFixture fixture) {
        assertThatThrownBy(() -> fixture.authority().readObjects(other, snapshot -> snapshot.commitId()))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("capacity is occupied by active or protected workspaces");
    }

    private Map<String, byte[]> files() {
        return Map.of(
                RepositoryPublishingPolicy.PATH,
                text("enabled: true\nmode: public-by-default\n"),
                "article.md",
                text("# Article\n![image](image.png)"),
                "image.png",
                image);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS))
                    .as("concurrent operation reached its barrier")
                    .isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class PayloadGate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final JGitRepositoryBlobReader blobs;

        PayloadGate(RepositoryAuthority real, RepositoryBlob descriptor) {
            var decorated = mock(RepositoryAuthority.class, delegatesTo(real));
            doAnswer(invocation -> {
                        RepositoryAuthority.ObjectReaderAction<?> action = invocation.getArgument(1);
                        return real.readImmutableObjects(invocation.getArgument(0), objects -> {
                            var gated = mock(ObjectReader.class, delegatesTo(objects));
                            // The real immutable tree is resolved before this barrier, but the exact blob
                            // payload is opened afterwards, exposing deletion of the source object store.
                            doAnswer(open -> {
                                        AnyObjectId object = open.getArgument(0);
                                        int type = open.getArgument(1);
                                        if (object.name().equals(descriptor.objectId()) && type == Constants.OBJ_BLOB) {
                                            entered.countDown();
                                            await(release);
                                        }
                                        return objects.open(object, type);
                                    })
                                    .when(gated)
                                    .open(any(AnyObjectId.class), anyInt());
                            return action.read(gated);
                        });
                    })
                    .when(decorated)
                    .readImmutableObjects(any(), any());
            blobs = new JGitRepositoryBlobReader(decorated);
        }
    }
}
