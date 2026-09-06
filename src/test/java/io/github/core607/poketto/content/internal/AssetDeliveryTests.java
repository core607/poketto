package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetDeliveryTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final MutableClock clock = new MutableClock();
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = actor();
    private final AtomicBoolean authorized = new AtomicBoolean(true);

    @BeforeEach
    void canonicalStorageRoot() throws Exception {
        // Windows may supply an 8.3 TEMP alias; the storage policy requires canonical ancestors.
        directory = directory.toRealPath();
    }

    @Test
    void publicResolutionUsesAstPathsPolicyAndSortedNonrecursiveGallery() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        Map<String, byte[]> files = files("notes/index.md", """
                # Gallery
                ![inline](./a.png)
                ![encoded](/%70rivate/secret.png)
                ![excluded](excluded.png)
                ![escape](../../escape.png)
                [article](article.md#section)
                [secret](../private/secret.md)
                `![code](b.png)`
                """);
        files.put("notes/a.png", png(1));
        files.put("notes/b.png", png(2));
        files.put("notes/c.png", png(3));
        files.put("notes/sub/nested.png", png(4));
        files.put("notes/excluded.png", png(5));
        files.put("private/secret.png", png(6));
        files.put("notes/article.md", text("---\nroute: /custom\n---\n# Article"));
        files.put("private/secret.md", text("# Secret"));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        AssetService service = service(fixture, snapshots);
        var result = service.publicDocument(workspace, "/notes").orElseThrow();
        assertThat(result.media().images()).containsOnlyKeys("./a.png");
        assertThat(result.media().links())
                .containsEntry("article.md#section", "/custom#section")
                .doesNotContainKey("../private/secret.md");
        assertThat(result.media().gallery()).extracting(item -> item.alt()).containsExactly("b.png", "c.png");
        assertThat(service.readPublicImage(
                                workspace, token(result.media().images().get("./a.png")))
                        .bytes())
                .isEqualTo(png(1));
        assertThat(fixture.cache(workspace).resolve("notes/a.png")).doesNotExist();
        assertThat(result.media().images().toString() + result.media().gallery())
                .doesNotContain("private/secret.png", "excluded.png", "escape.png");
    }

    @Test
    void ordinaryArticlesHaveNoGalleryAndExplicitSymlinkIsNotAnImage() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = files("article.md", "# Article\n![symlink](link.png)");
        files.put("a.png", png(1));
        files.put("link.png", text("a.png"));
        fixture.commitRemote(workspace, files, Map.of("link.png", FileMode.SYMLINK));
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var result = service(fixture, snapshots)
                .publicDocument(workspace, "/article")
                .orElseThrow();
        assertThat(result.media().gallery()).isEmpty();
        assertThat(result.media().images()).isEmpty();
    }

    @Test
    void oldGrantKeepsExactBytesAfterWithdrawalAndDerivedCacheDeletion() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var oldFiles = files("article.md", "# Article\n![image](image.png)");
        oldFiles.put("image.png", png(1));
        String old = fixture.commitRemote(workspace, oldFiles).name();
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        AssetService service = service(fixture, snapshots);
        String grant = token(service.publicDocument(workspace, "/article")
                .orElseThrow()
                .media()
                .images()
                .get("image.png"));
        var withdrawn = files("private/article.md", "# Private");
        withdrawn.put("image.png", png(2));
        fixture.commitRemote(workspace, withdrawn);
        snapshots.refresh(workspace);
        assertThat(service.publicDocument(workspace, "/article")).isEmpty();
        Path cache = directory.resolve("image-cache");
        try (var entries = Files.list(cache)) {
            for (Path entry : entries.toList()) Files.delete(entry);
        }
        Files.delete(cache);
        var replay = service.readPublicImage(workspace, grant);
        assertThat(replay.bytes()).isEqualTo(png(1));
        assertThat(((AssetSource.Repository) replay.source()).commit()).contains(old);
        assertThat(directory.resolve("image-cache")).isDirectory();
        assertNotFound(() -> service.readPublicImage(WorkspaceId.random(), grant));
        clock.now = clock.now.plusSeconds(300);
        assertNotFound(() -> service.readPublicImage(workspace, grant));
    }

    @Test
    void acknowledgedWithdrawalWithLockedCacheRefClosesNewReadsAndOfflineRestoration() throws Exception {
        var delegate = new JGitRemoteGitTransport();
        AtomicBoolean offline = new AtomicBoolean();
        RemoteGitTransport transport = new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                if (offline.get()) throw new RemoteGitTransportException("synthetic offline authority");
                return delegate.fetchMain(repository, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                PushStatus status = delegate.pushMain(repository, binding, expected, candidate);
                try {
                    Path lock = repository.getDirectory().toPath().resolve("refs/heads/main.lock");
                    Files.createDirectories(lock.getParent());
                    Files.writeString(lock, "synthetic local ref lock after remote acknowledgement");
                } catch (java.io.IOException failure) {
                    throw new RuntimeException(failure);
                }
                return status;
            }
        };
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var files = files("article.md", "# Public\n![image](image.png)");
        files.put("other.md", text("# Another public page\n![image](image.png)"));
        files.put("image.png", png(1));
        var base = fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        String issued = token(service.publicDocument(workspace, "/article")
                .orElseThrow()
                .media()
                .images()
                .get("image.png"));
        var patches = new JGitRepositoryPatchService(
                fixture.authority(), auth, clock, snapshots::installAcknowledged, snapshots::closePublication);
        var change = new RepositoryTextChange(
                RepositoryPublishingPolicy.PATH,
                false,
                Optional.of(DocumentRevision.sha256(files.get(RepositoryPublishingPolicy.PATH))),
                Optional.of("enabled: false\nmode: public-by-default\n"));
        assertThatThrownBy(() ->
                        patches.apply(actor, workspace, new RepositoryPatch(Optional.of(base.name()), List.of(change))))
                .isInstanceOf(RepositoryWriteAmbiguousException.class)
                .hasMessageContaining("remote acknowledged");
        assertThat(fixture.remoteHead(workspace)).isNotEqualTo(base);
        // Previously issued authorization remains valid for its exact bytes, even after withdrawal.
        assertThat(service.readPublicImage(workspace, issued).bytes()).isEqualTo(png(1));
        offline.set(true);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThatThrownBy(() -> snapshots.current(workspace))
                        .as("known remote withdrawal must close public search and listings")
                        .isInstanceOf(ContentRepositoryException.class),
                () -> assertThatThrownBy(() -> service.publicDocument(workspace, "/other"))
                        .as("known remote withdrawal must prevent new image grants")
                        .isInstanceOf(ContentRepositoryException.class),
                () -> assertThatThrownBy(
                                () -> snapshots(fixture, Duration.ofHours(1)).ensureReady(workspace))
                        .as("offline restart must not restore the earlier OPEN marker")
                        .isInstanceOf(ContentRepositoryException.class));
        offline.set(false);
        Files.delete(fixture.cache(workspace).resolve(".git/refs/heads/main.lock"));
        assertThat(snapshots.refresh(workspace).articles()).isEmpty();
        assertThat(service.publicDocument(workspace, "/other")).isEmpty();
        assertThat(service.readPublicImage(workspace, issued).bytes()).isEqualTo(png(1));
        clock.now = clock.now.plusSeconds(300);
        assertNotFound(() -> service.readPublicImage(workspace, issued));
    }

    @Test
    void failureToPersistClosedPublicationPreventsSubmittingThePublicPatch() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = files("article.md", "# Original");
        var base = fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        service(fixture, snapshots);
        var patches = new JGitRepositoryPatchService(
                fixture.authority(), auth, clock, snapshots::installAcknowledged, snapshots::closePublication);
        Path blockedMarker = fixture.cache(workspace).resolve(".git/poketto-public-snapshot.tmp");
        Files.createDirectory(blockedMarker);
        var change = new RepositoryTextChange(
                "article.md",
                false,
                Optional.of(DocumentRevision.sha256(files.get("article.md"))),
                Optional.of("# Edited"));
        assertThatThrownBy(() ->
                        patches.apply(actor, workspace, new RepositoryPatch(Optional.of(base.name()), List.of(change))))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("snapshot state cannot be recorded");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
        Files.delete(blockedMarker);
        assertThat(snapshots.refresh(workspace).articles())
                .extracting(article -> article.title())
                .containsExactly("Original");
    }

    @Test
    void grantCannotOutliveTheIssuingSnapshot() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = files("article.md", "![image](image.png)");
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofSeconds(10));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        String grant = token(service.publicDocument(workspace, "/article")
                .orElseThrow()
                .media()
                .images()
                .get("image.png"));
        clock.now = clock.now.plusSeconds(9);
        assertThat(service.readPublicImage(workspace, grant).bytes()).isEqualTo(png(1));
        clock.now = clock.now.plusSeconds(1);
        assertNotFound(() -> service.readPublicImage(workspace, grant));
    }

    @Test
    void privatePreviewUsesRepositoryParserAndRechecksCurrentAuthorizationForEveryImage() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = files("private/article.md", "# Private");
        files.put("private/image.png", png(7));
        String commit = fixture.commitRemote(workspace, files).name();
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        AssetService service = service(fixture, snapshots);
        var preview = service.preview(
                actor,
                workspace,
                "private/article.md",
                "---\ntitle: Draft\n---\n# Edited\n![image](image.png)",
                Optional.of(commit));
        assertThat(preview.body()).isEqualTo("# Edited\n![image](image.png)");
        assertThat(preview.commit()).isEqualTo(commit);
        String grant = token(preview.images().get("image.png"));
        assertThat(service.readPrivateImage(actor, workspace, grant).bytes()).isEqualTo(png(7));
        assertNotFound(() -> service.readPrivateImage(actor(), workspace, grant));
        assertNotFound(() -> service.readPublicImage(workspace, grant));
        authorized.set(false);
        assertThatThrownBy(() -> service.readPrivateImage(actor, workspace, grant))
                .isInstanceOf(SecurityException.class);
        verify(auth, atLeast(4)).withAuthorization(any(), eq(workspace), eq(Set.of(Capability.READ_PRIVATE)), any());
    }

    @Test
    void privateInventoryValidatesActualMediaAndReportsInvalidCandidates() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = files("private/article.md", "# Private");
        files.put("private/image.png", png(7));
        files.put("private/broken.jpg", text("not a JPEG"));
        files.put("other.png", png(1));
        String commit = fixture.commitRemote(workspace, files).name();
        var service = service(fixture, snapshots(fixture, Duration.ofHours(1)));
        var inventory = service.repositoryImages(actor, workspace, Optional.of(commit), "private/", 0, 30);
        assertThat(inventory.items()).hasSize(1);
        assertThat(inventory.items().getFirst().path()).isEqualTo("private/image.png");
        assertThat(inventory.items().getFirst().mediaType()).isEqualTo("image/png");
        assertThat(inventory.total()).isEqualTo(1);
        assertThat(inventory.diagnostics()).extracting(item -> item.path()).containsExactly("private/broken.jpg");
        assertThatThrownBy(() -> service.preview(
                        actor, workspace, "private/article.md", "---\nbad: [\n---\nbody", Optional.of(commit)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentCallbackSerializesImageMintingWithSnapshotInstallation() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, files("article.md", "# Public"));
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        fixture.commitRemote(workspace, files("private/article.md", "# Private"));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshing = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reading = pool.submit(() -> snapshots.withCurrent(workspace, snapshot -> {
                inside.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException interrupted) {
                    throw new RuntimeException(interrupted);
                }
                return snapshot.articles().size();
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            var updating = pool.submit(() -> {
                refreshing.countDown();
                return snapshots.refresh(workspace);
            });
            assertThat(refreshing.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> updating.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(reading.get(5, TimeUnit.SECONDS)).isOne();
            assertThat(updating.get(5, TimeUnit.SECONDS).articles()).isEmpty();
            assertThat(service(fixture, snapshots).publicDocument(workspace, "/article"))
                    .isEmpty();
        }
    }

    private AssetService service(RemoteRepositoryFixture fixture, JGitPublicContentSnapshots snapshots) {
        when(auth.withAuthorization(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (!authorized.get()) throw new SecurityException("authorization revoked");
            return ((Supplier<?>) invocation.getArgument(3)).get();
        });
        return new AssetService(
                auth,
                new JGitRepositoryContentReader(fixture.authority()),
                new JGitRepositoryBlobReader(fixture.authority()),
                new RepositoryMarkdownConfiguration().repositoryMarkdownInspector(),
                snapshots,
                () -> mock(ManagedBlobStore.class),
                directory.resolve("image-cache"),
                16L * 1024 * 1024,
                128,
                clock);
    }

    private JGitPublicContentSnapshots snapshots(RemoteRepositoryFixture fixture, Duration lifetime) {
        return new JGitPublicContentSnapshots(fixture.authority(), clock, lifetime);
    }

    private static Map<String, byte[]> files(String path, String body) {
        var files = new LinkedHashMap<String, byte[]>();
        files.put(
                RepositoryPublishingPolicy.PATH,
                text("enabled: true\nmode: public-by-default\nexclude: ['notes/excluded.png']\n"));
        files.put(path, text(body));
        return files;
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static AuthPrincipal actor() {
        AuthPrincipal actor = mock(AuthPrincipal.class);
        UUID id = UUID.randomUUID();
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(actor.subjectId()).thenReturn(id);
        when(actor.accountId()).thenReturn(id);
        return actor;
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.NOT_FOUND));
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
