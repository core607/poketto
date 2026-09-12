package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class MediaFileServiceTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final RepositoryBlobReader repository = mock(RepositoryBlobReader.class);
    private final PublicContentSnapshots snapshots = mock(PublicContentSnapshots.class);
    private final String commit = "a".repeat(40);
    private ManagedBlobStore store;
    private MediaFileService service;
    private ManagedAsset asset;
    private PublicContentSnapshot snapshot;
    private final byte[] bytes = new byte[1024 * 1024];

    @BeforeEach
    void prepare() {
        Arrays.fill(bytes, (byte) 42);
        store = ManagedBlobStore.local(directory.resolve("originals"));
        asset = store.uploadFile(workspace, "synthetic-media-01", "application/pdf", new ByteArrayInputStream(bytes));
        var entry = new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        var index = new RepositoryMediaIndex(Map.of("public/source.pdf", entry, "private/source.pdf", entry));
        when(repository.selectCommit(eq(workspace), any())).thenReturn(Optional.of(commit));
        when(repository.media(workspace, commit))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, index, Set.of("public/source.pdf")));
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        snapshot = new PublicContentSnapshot(
                workspace,
                Optional.of(commit),
                now,
                now.plusSeconds(300),
                List.of(new PublicArticle(
                        "public/note.md",
                        "/note",
                        "Note",
                        "[Source](source.pdf)\n[Private](../private/source.pdf)",
                        List.of(),
                        now,
                        now,
                        false)));
        when(snapshots.withCurrent(eq(workspace), any()))
                .thenAnswer(
                        invocation -> ((Function<PublicContentSnapshot, ?>) invocation.getArgument(1)).apply(snapshot));
        service = new MediaFileService(auth, repository, snapshots, () -> store);
    }

    @Test
    void originalMetadataIsWorkspaceBoundAndRechecksReadPermissionBeforeReturning() {
        assertThat(service.describeOriginal(actor, workspace, asset.reference()))
                .isEqualTo(asset);
        assertMissing(() -> service.describeOriginal(actor, WorkspaceId.random(), asset.reference()));
        reset(auth);
        when(auth.authorize(actor, workspace, Capability.READ_PRIVATE))
                .thenReturn(null)
                .thenThrow(new IllegalStateException("revoked"));
        assertThatThrownBy(() -> service.describeOriginal(actor, workspace, asset.reference()))
                .hasMessage("revoked");
    }

    @Test
    void downloadsExactPrivateAndReferencedPublicBytesButRejectsPrivateAndStalePublicTargets() {
        var output = new ByteArrayOutputStream();
        service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf")
                .writeTo(output);
        assertThat(output.toByteArray()).isEqualTo(bytes);
        output.reset();
        service.publicDownload(workspace, commit, "/note", "public/source.pdf").writeTo(output);
        assertThat(output.toByteArray()).isEqualTo(bytes);
        assertMissing(() -> service.publicDownload(workspace, commit, "/note", "private/source.pdf"));
        assertMissing(() -> service.publicDownload(workspace, "b".repeat(40), "/note", "public/source.pdf"));
        assertMissing(() -> service.publicDownload(workspace, commit, "/absent", "public/source.pdf"));
        var foreign = WorkspaceId.random();
        when(repository.selectCommit(eq(foreign), any())).thenReturn(Optional.of(commit));
        var originalCatalog = repository.media(workspace, commit);
        when(repository.media(foreign, commit)).thenReturn(originalCatalog);
        assertMissing(() -> service.privateDownload(actor, foreign, Optional.empty(), "private/source.pdf"));
    }

    @Test
    void privateRevocationAndPublicWithdrawalStopStreamingWithinTheAuthorizedBlock() {
        AtomicBoolean revoked = new AtomicBoolean();
        doAnswer(invocation -> {
                    if (revoked.get()) {
                        throw new IllegalStateException("revoked");
                    }
                    return null;
                })
                .when(auth)
                .authorize(actor, workspace, Capability.READ_PRIVATE);
        var download = service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf");
        AtomicLong count = new AtomicLong();
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) {
                count.incrementAndGet();
                revoked.set(true);
            }

            @Override
            public void write(byte[] data, int offset, int length) {
                count.addAndGet(length);
                revoked.set(true);
            }
        };
        assertThatThrownBy(() -> download.writeTo(output)).hasMessage("revoked");
        assertThat(count.get()).isPositive().isLessThanOrEqualTo(256 * 1024);
        var publicDownload = service.publicDownload(workspace, commit, "/note", "public/source.pdf");
        snapshot = new PublicContentSnapshot(
                workspace, Optional.of(commit), snapshot.verifiedAt(), snapshot.expiresAt(), List.of());
        assertMissing(() -> publicDownload.writeTo(OutputStream.nullOutputStream()));
    }

    @Test
    void completedDownloadDoesNotRecheckAfterItsLastByteAndFailuresKeepTheirCause() {
        var revoked = new AtomicBoolean();
        doAnswer(call -> {
                    if (revoked.get()) {
                        throw new IllegalStateException("revoked");
                    }
                    return null;
                })
                .when(auth)
                .authorize(actor, workspace, Capability.READ_PRIVATE);
        var count = new AtomicLong();
        service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf")
                .writeTo(new OutputStream() {
                    @Override
                    public void write(int value) {
                        if (count.incrementAndGet() == bytes.length) {
                            revoked.set(true);
                        }
                    }

                    @Override
                    public void write(byte[] data, int offset, int length) {
                        if (count.addAndGet(length) == bytes.length) {
                            revoked.set(true);
                        }
                    }
                });
        assertThat(count).hasValue(bytes.length);
        assertThatThrownBy(() -> service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf"))
                .hasMessage("revoked");
        revoked.set(false);
        var download = service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf");
        assertThatThrownBy(() -> download.writeTo(new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        revoked.set(true);
                        throw new IOException("synthetic write failure");
                    }

                    @Override
                    public void write(byte[] data, int offset, int length) throws IOException {
                        write(0);
                    }
                }))
                .hasMessage("revoked")
                .satisfies(error -> assertThat(error.getSuppressed())
                        .anyMatch(failure -> failure instanceof AssetStorageException));
    }

    @Test
    void activeTransfersEnforceWorkspaceAndInstanceLimitsAndReleaseCapacity() throws Exception {
        var other = WorkspaceId.random();
        var otherAsset =
                store.uploadFile(other, "other-transfer-01", "application/pdf", new ByteArrayInputStream(bytes));
        when(repository.selectCommit(eq(other), any())).thenReturn(Optional.of(commit));
        when(repository.media(other, commit))
                .thenReturn(new RepositoryMediaSnapshot(
                        other,
                        commit,
                        new RepositoryMediaIndex(Map.of(
                                "private/source.pdf",
                                new RepositoryMediaIndex.Media(
                                        otherAsset.reference().assetId(),
                                        otherAsset.reference().revision(),
                                        otherAsset.mediaType(),
                                        otherAsset.size()))),
                        Set.of()));
        var firstTwo = new CountDownLatch(2);
        var allFour = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] data, int offset, int length) throws IOException {
                firstTwo.countDown();
                allFour.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("fixture timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 2; i++) {
                    futures.add(executor.submit(
                            () -> service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf")
                                    .writeTo(blocked)));
                }
                assertThat(firstTwo.await(5, TimeUnit.SECONDS)).isTrue();
                assertUnavailable(
                        () -> service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf")
                                .writeTo(OutputStream.nullOutputStream()));
                for (int i = 0; i < 2; i++) {
                    futures.add(executor.submit(
                            () -> service.privateDownload(actor, other, Optional.empty(), "private/source.pdf")
                                    .writeTo(blocked)));
                }
                assertThat(allFour.await(5, TimeUnit.SECONDS)).isTrue();
                var untouched = new ByteArrayInputStream(bytes);
                assertUnavailable(() -> service.upload(
                        actor, WorkspaceId.random(), "saturated-upload-01", "application/pdf", untouched));
                assertThat(untouched.available()).isEqualTo(bytes.length);
            } finally {
                release.countDown();
                for (var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }
        }
        var output = new ByteArrayOutputStream();
        service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf")
                .writeTo(output);
        assertThat(output.toByteArray()).isEqualTo(bytes);
    }

    @Test
    void stalledPublicReadersLeaveWorkspaceAndInstanceCapacityForAuthorizedTransfers() throws Exception {
        var workspaces = List.of(workspace, WorkspaceId.random(), WorkspaceId.random());
        for (var selected : workspaces.subList(1, workspaces.size())) {
            var original = store.uploadFile(
                    selected, "public-capacity-01", "application/pdf", new ByteArrayInputStream(bytes));
            var entry = new RepositoryMediaIndex.Media(
                    original.reference().assetId(),
                    original.reference().revision(),
                    original.mediaType(),
                    original.size());
            when(repository.media(selected, commit))
                    .thenReturn(new RepositoryMediaSnapshot(
                            selected,
                            commit,
                            new RepositoryMediaIndex(Map.of("public/source.pdf", entry)),
                            Set.of("public/source.pdf")));
            var publicSnapshot = new PublicContentSnapshot(
                    selected, snapshot.commit(), snapshot.verifiedAt(), snapshot.expiresAt(), snapshot.articles());
            when(snapshots.withCurrent(eq(selected), any()))
                    .thenAnswer(
                            call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(publicSnapshot));
        }
        var started = new CountDownLatch(2);
        var firstStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] data, int offset, int length) throws IOException {
                started.countDown();
                firstStarted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("fixture timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var readers = new ArrayList<Future<?>>();
            try {
                readers.add(
                        executor.submit(() -> service.publicDownload(workspace, commit, "/note", "public/source.pdf")
                                .writeTo(blocked)));
                assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertUnavailable(() -> service.publicDownload(workspace, commit, "/note", "public/source.pdf")
                        .writeTo(OutputStream.nullOutputStream()));
                readers.add(executor.submit(
                        () -> service.publicDownload(workspaces.get(1), commit, "/note", "public/source.pdf")
                                .writeTo(blocked)));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                clearInvocations(repository);
                for (var selected : workspaces) {
                    assertUnavailable(() -> service.publicDownload(selected, commit, "/note", "public/source.pdf")
                            .writeTo(OutputStream.nullOutputStream()));
                }
                verifyNoInteractions(repository);
                for (var selected : workspaces.subList(0, 2)) {
                    var uploaded = service.upload(
                            actor, selected, "reserved-upload-01", "application/pdf", new ByteArrayInputStream(bytes));
                    assertThat(uploaded.size()).isEqualTo(bytes.length);
                }
                var privateBytes = new ByteArrayOutputStream();
                service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf")
                        .writeTo(privateBytes);
                assertThat(privateBytes.toByteArray()).isEqualTo(bytes);
            } finally {
                release.countDown();
                for (var reader : readers) {
                    reader.get(5, TimeUnit.SECONDS);
                }
            }
        }
        var publicBytes = new ByteArrayOutputStream();
        service.publicDownload(workspace, commit, "/note", "public/source.pdf").writeTo(publicBytes);
        assertThat(publicBytes.toByteArray()).isEqualTo(bytes);
    }

    private static void assertUnavailable(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.UNAVAILABLE));
    }

    @Test
    void corruptionWritesNothingAndUploadBoundsDoNotAcknowledgeOrPublish() throws Exception {
        var download = service.privateDownload(actor, workspace, Optional.empty(), "private/source.pdf");
        Path original = directory
                .resolve("originals")
                .resolve(workspace.toString())
                .resolve("objects")
                .resolve(asset.reference().assetId().toString())
                .resolve("bytes");
        Files.writeString(original, "corrupt");
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> download.writeTo(output)).isInstanceOf(AssetStorageException.class);
        assertThat(output.size()).isZero();
        var boundedStore = ManagedBlobStore.local(directory.resolve("bounded"), 32);
        var bounded = new MediaFileService(auth, repository, snapshots, () -> boundedStore);
        assertThatThrownBy(() -> bounded.upload(
                        actor, workspace, "bounded-upload-01", "text/html", new ByteArrayInputStream(new byte[33])))
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AssetStorageException.Reason.TOO_LARGE));
        assertThat(boundedStore.list(workspace, 0, 100).items()).isEmpty();
        var uploaded = bounded.upload(
                actor, workspace, "bounded-upload-01", "text/html", new ByteArrayInputStream(new byte[32]));
        assertThat(uploaded.mediaType()).isEqualTo("text/html");
        verify(auth, never()).authorize(actor, workspace, Capability.PUBLISH);
    }

    private static void assertMissing(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.NOT_FOUND));
    }
}
