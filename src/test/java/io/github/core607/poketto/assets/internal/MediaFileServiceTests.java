package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.*;
import io.github.core607.poketto.auth.*;
import io.github.core607.poketto.content.*;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
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
        java.util.Arrays.fill(bytes, (byte) 42);
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
                    if (revoked.get()) throw new IllegalStateException("revoked");
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

    private static void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.NOT_FOUND));
    }
}
