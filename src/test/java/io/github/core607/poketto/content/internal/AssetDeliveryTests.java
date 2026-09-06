package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.ManagedImage;
import io.github.core607.poketto.assets.ResolvedMedia;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.SiblingImages;
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
import java.util.ArrayList;
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
import java.util.function.Function;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
    void logicalRoutesResolveEncodedMarkdownLinksAndImagesWithoutReinterpretingNames() throws Exception {
        assertLogicalMedia("目录 空格%#");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxQuestionMarkFolderSupportsPublicGalleryAndPrivatePreview() throws Exception {
        assertLogicalMedia("目录 空格%#?");
    }

    private void assertLogicalMedia(String folder) throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        String source = """
                # 文件夹
                [percent](100%25.md)
                [hash](note%23part.md#Section)
                [encoded](literal%252Fslash.md)
                [real](literal/slash.md)
                [encodedUnicode](%25E9%259B%25A8.md)
                [unicode](%E9%9B%A8.md)
                [custom](custom.md)
                [private](/private/hidden%20%25%23.md)
                ![image](photo%20%25%23.png)
                """;
        var files = files(folder + "/index.md", source);
        for (String name : new String[] {
            "100%.md", "note#part.md", "literal%2Fslash.md", "literal/slash.md", "%E9%9B%A8.md", "雨.md"
        }) {
            files.put(folder + "/" + name, text("# " + name));
        }
        files.put(folder + "/custom.md", text("---\nroute: '/chosen ?%# '\n---\n# Custom"));
        files.put(folder + "/photo %#.png", png(1));
        files.put(folder + "/other %#.png", png(2));
        files.put(folder + "/nested/image.png", png(3));
        files.put("private/hidden %#.md", text("# Private"));
        var commit = fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        var result = service.publicDocument(workspace, "/" + folder).orElseThrow();
        assertThat(result.media().body()).isEqualTo(source);
        assertThat(result.media().links())
                .containsEntry("100%25.md", "/" + folder + "/100%")
                .containsEntry("note%23part.md#Section", "/" + folder + "/note#part#Section")
                .containsEntry("literal%252Fslash.md", "/" + folder + "/literal%2Fslash")
                .containsEntry("literal/slash.md", "/" + folder + "/literal/slash")
                .containsEntry("%25E9%259B%25A8.md", "/" + folder + "/%E9%9B%A8")
                .containsEntry("%E9%9B%A8.md", "/" + folder + "/雨")
                .containsEntry("custom.md", "/chosen ?%# ")
                .doesNotContainKey("/private/hidden%20%25%23.md");
        assertThat(result.media().gallery()).extracting(item -> item.alt()).containsExactly("other %#.png");
        assertThat(service.readPublicImage(
                                workspace, token(result.media().images().get("photo%20%25%23.png")))
                        .bytes())
                .isEqualTo(png(1));
        assertThat(service.publicDocument(workspace, "/" + folder + "/100%")
                        .orElseThrow()
                        .media()
                        .gallery())
                .isEmpty();
        assertThat(service.publicDocument(workspace, "/chosen ?%# ")).isPresent();
        assertThat(service.publicDocument(workspace, "/chosen ?%#")).isEmpty();
        assertThat(service.publicDocument(workspace, "/private/hidden %#")).isEmpty();
        var preview = service.preview(actor, workspace, folder + "/index.md", source, Optional.of(commit.name()));
        assertThat(preview.body()).isEqualTo(source);
        assertThat(preview.links())
                .containsEntry(
                        "100%25.md",
                        "/admin?path=" + java.net.URLEncoder.encode(folder + "/100%.md", StandardCharsets.UTF_8));
        assertThat(service.readPrivateImage(
                                actor, workspace, token(preview.images().get("photo%20%25%23.png")))
                        .bytes())
                .isEqualTo(png(1));
    }

    @Test
    void publicResolutionUsesAstPathsPolicyAndSortedNonrecursiveGallery() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
    void overflowingGalleryPreservesTheDocumentAndFirst128Images() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("index.md", "# Still readable");
        for (int i = 0; i < 129; i++) files.put("image-%03d.png".formatted(i), png(i));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var media = service(fixture, snapshots)
                .publicDocument(workspace, "/")
                .orElseThrow()
                .media();
        assertThat(media.body()).isEqualTo("# Still readable");
        assertThat(media.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.PARTIAL);
        assertThat(media.gallery()).hasSize(128);
        assertThat(media.gallery().getLast().alt()).isEqualTo("image-127.png");
    }

    @Test
    void exactly128ImagesRemainCompleteAndUnicodeTruncationKeepsJavaFilenameOrder() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("目录/index.md", "# Gallery");
        for (int i = 0; i < 126; i++) files.put("目录/image-%03d.png".formatted(i), png(i));
        files.put("目录/中文.png", png(1));
        files.put("目录/\ue000.png", png(2));
        var commit = fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var complete = service(fixture, snapshots)
                .publicDocument(workspace, "/目录")
                .orElseThrow()
                .media();
        assertThat(complete.gallery()).hasSize(128);
        assertThat(complete.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.COMPLETE);
        files.put("目录/😀.png", png(3));
        commit = fixture.commitRemote(workspace, files);
        snapshots.refresh(workspace);
        var reader = new JGitRepositoryBlobReader(fixture.authority());
        var result = reader.siblings(workspace, commit.name(), "目录/index.md", 128, true, Set.of());
        var expected = files.keySet().stream()
                .filter(path -> path.endsWith(".png"))
                .sorted()
                .limit(128)
                .toList();
        assertThat(result.partial()).isTrue();
        assertThat(result.items()).extracting(RepositoryBlob::path).containsExactlyElementsOf(expected);
        assertThat(result.items().getLast().path()).isEqualTo("目录/😀.png");
    }

    @Test
    void normalizedInlineAndExcludedPathsDoNotConsumeSlotsOrRevealTruncation() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var source = new StringBuilder("# Body\n");
        var files = files("notes/index.md", "");
        for (int i = 0; i < 129; i++) {
            String name = "inline-%03d.png".formatted(i);
            files.put(
                    "notes/" + name,
                    i == 128 ? new byte[RepositoryBlobReader.MAX_BLOB_BYTES + 1] : text("invalid image"));
            source.append("![already referenced](./")
                    .append(name.replace("inline", "%69nline"))
                    .append(")\n");
        }
        files.put("notes/visible.png", png(1));
        files.put("notes/index.md", text(source.toString()));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        var before = service.publicDocument(workspace, "/notes").orElseThrow().media();
        assertThat(before.images()).isEmpty();
        assertThat(before.gallery()).extracting(ResolvedMedia.GalleryImage::alt).containsExactly("visible.png");
        assertThat(before.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.COMPLETE);
        files.put(
                RepositoryPublishingPolicy.PATH,
                text("enabled: true\nmode: public-by-default\nexclude: ['notes/hidden-*.png']\n"));
        for (int i = 0; i < 129; i++) {
            files.put("notes/hidden-%03d.png".formatted(i), png(i));
            files.put("private/image-%03d.png".formatted(i), png(i));
            files.put("notes/nested/image-%03d.png".formatted(i), png(i));
        }
        var commit = fixture.commitRemote(workspace, files);
        snapshots.refresh(workspace);
        var after = service.publicDocument(workspace, "/notes").orElseThrow().media();
        assertThat(after.gallery()).extracting(ResolvedMedia.GalleryImage::alt).containsExactly("visible.png");
        assertThat(after.galleryStatus()).isEqualTo(before.galleryStatus());
        var reader = new JGitRepositoryBlobReader(fixture.authority());
        var denied = reader.siblings(workspace, commit.name(), "private/index.md", 128, true, Set.of());
        assertThat(denied.items()).isEmpty();
        assertThat(denied.partial()).isFalse();
        var authorized = reader.siblings(workspace, commit.name(), "private/index.md", 128, false, Set.of());
        assertThat(authorized.items()).hasSize(128);
        assertThat(authorized.partial()).isTrue();
    }

    @Test
    void corruptAndOversizedSiblingsYieldPartialWithoutFailingTextOrRefillingCandidates() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("index.md", "# Still readable");
        files.put("a.png", new byte[RepositoryBlobReader.MAX_BLOB_BYTES + 1]);
        files.put("b.png", text("not a PNG"));
        files.put("c.png", png(1));
        for (int i = 3; i < 129; i++) files.put("z-%03d.png".formatted(i), png(i));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var media = service(fixture, snapshots)
                .publicDocument(workspace, "/")
                .orElseThrow()
                .media();
        assertThat(media.body()).isEqualTo("# Still readable");
        assertThat(media.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.PARTIAL);
        assertThat(media.gallery()).hasSize(126);
        assertThat(media.gallery())
                .extracting(ResolvedMedia.GalleryImage::alt)
                .doesNotContain("a.png", "b.png", "z-128.png");
    }

    @Test
    void emptyPartialGalleryAndMissingPreviewFolderHaveDistinctStatuses() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        text("enabled: true\nmode: public-by-default\n"),
                        "index.md",
                        text("# Text"),
                        "invalid.png",
                        text("invalid")));
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        var media = service.publicDocument(workspace, "/").orElseThrow().media();
        assertThat(media.gallery()).isEmpty();
        assertThat(media.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.PARTIAL);
        var preview = service.preview(actor, workspace, "new/folder/index.md", "# Draft", Optional.empty());
        assertThat(preview.body()).isEqualTo("# Draft");
        assertThat(preview.gallery()).isEmpty();
        assertThat(preview.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.COMPLETE);
    }

    @Test
    void imageCacheFailureProducesUnavailableGalleryWhileSnapshotExpiryStillFailsClosed() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("index.md", "# Readable");
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        Files.createDirectories(directory.resolve("image-cache"));
        Files.writeString(directory.resolve("image-cache/unexpected"), "isolated invalid cache entry");
        var service = service(fixture, snapshots);
        var media = service.publicDocument(workspace, "/").orElseThrow().media();
        assertThat(media.body()).isEqualTo("# Readable");
        assertThat(media.gallery()).isEmpty();
        assertThat(media.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.UNAVAILABLE);
        clock.now = clock.now.plusSeconds(3600);
        assertThatThrownBy(() -> service.publicDocument(workspace, "/")).isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void bodyImagesHavePriorityAndExhaustedPageBudgetDoesNotReadMoreManagedOrGitBytes() {
        var store = mock(ManagedBlobStore.class);
        var blobs = mock(RepositoryBlobReader.class);
        var references = new ArrayList<ManagedAssetReference>();
        var source = new StringBuilder("# Text survives\n");
        for (int i = 0; i < 9; i++) {
            var reference = new ManagedAssetReference(UUID.randomUUID(), "a".repeat(64));
            references.add(reference);
            source.append("![Image](").append(reference).append(")\n");
        }
        var image = new ManagedImage(
                new ManagedAsset(references.getFirst(), "image/png", ManagedBlobStore.MAX_UPLOAD_BYTES),
                new byte[ManagedBlobStore.MAX_UPLOAD_BYTES]);
        when(store.read(any(), any())).thenReturn(image);
        var gallery = new RepositoryBlob(workspace, "b".repeat(40), "sibling.png", "c".repeat(40), 1, true);
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(gallery), false));
        var media = pageService(blobs, store, source.toString())
                .publicDocument(workspace, "/")
                .orElseThrow()
                .media();
        assertThat(media.body()).isEqualTo(source.toString());
        assertThat(media.images())
                .hasSize(8)
                .doesNotContainKey(references.getLast().toString());
        verify(store, times(8)).read(any(), any());
        verify(store, never()).read(workspace, references.getLast());
        verify(blobs, never()).read(any());
        assertThat(media.gallery()).isEmpty();
        assertThat(media.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.PARTIAL);
    }

    @Test
    void successfulSmallManagedReadsReturnUnusedPageAllowance() {
        var store = mock(ManagedBlobStore.class);
        var blobs = mock(RepositoryBlobReader.class);
        var reference = new ManagedAssetReference(UUID.randomUUID(), "a".repeat(64));
        var source = new StringBuilder("# Text\n");
        for (int i = 0; i < 20; i++)
            source.append("![Image](managed:")
                    .append(UUID.randomUUID())
                    .append(":")
                    .append("a".repeat(64))
                    .append(")\n");
        when(store.read(any(), any()))
                .thenReturn(new ManagedImage(new ManagedAsset(reference, "image/png", 1), new byte[] {1}));
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), false));
        var media = pageService(blobs, store, source.toString())
                .publicDocument(workspace, "/")
                .orElseThrow()
                .media();
        assertThat(media.images()).hasSize(20);
        verify(store, times(20)).read(any(), any());
        assertThat(media.galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.COMPLETE);
    }

    @Test
    void failedGitTargetsChargeOnceAndKnownSizeIsCheckedBeforeMaterialization() {
        var blobs = mock(RepositoryBlobReader.class);
        var source = new StringBuilder("# Text\n![one](first.png)\n![same](./first.png)\n");
        var first = new RepositoryBlob(
                workspace, "b".repeat(40), "first.png", "c".repeat(40), RepositoryBlobReader.MAX_BLOB_BYTES, true);
        when(blobs.find(workspace, "b".repeat(40), "first.png")).thenReturn(Optional.of(first));
        for (int i = 1; i < 9; i++) {
            String path = "image-" + i + ".png";
            var blob = new RepositoryBlob(
                    workspace, "b".repeat(40), path, "c".repeat(40), RepositoryBlobReader.MAX_BLOB_BYTES, true);
            when(blobs.find(workspace, "b".repeat(40), path)).thenReturn(Optional.of(blob));
            source.append("![image](").append(path).append(")\n");
        }
        when(blobs.read(any())).thenThrow(new ContentRepositoryException("isolated object failure"));
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), false));
        var media = pageService(blobs, mock(ManagedBlobStore.class), source.toString())
                .publicDocument(workspace, "/")
                .orElseThrow()
                .media();
        assertThat(media.body()).isEqualTo(source.toString());
        assertThat(media.images()).isEmpty();
        verify(blobs).read(first);
        verify(blobs, times(8)).read(any());
        verify(blobs, never()).read(argThat(blob -> blob.path().equals("image-8.png")));
    }

    private AssetService pageService(RepositoryBlobReader blobs, ManagedBlobStore store, String body) {
        Instant now = clock.instant();
        var snapshot = new PublicContentSnapshot(
                workspace,
                Optional.of("b".repeat(40)),
                now,
                now.plusSeconds(3600),
                List.of(new PublicArticle("index.md", "/", "Text", body, List.of(), now, now, true)));
        var snapshots = mock(PublicContentSnapshots.class);
        when(snapshots.withCurrent(any(), any()))
                .thenAnswer(call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(snapshot));
        return new AssetService(
                null,
                null,
                blobs,
                null,
                snapshots,
                () -> store,
                directory.resolve("page-cache"),
                16L * 1024 * 1024,
                128,
                clock);
    }

    @Test
    void ordinaryArticlesHaveNoGalleryAndExplicitSymlinkIsNotAnImage() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
        var fixture = new RemoteRepositoryFixture(directory, transport, clock);
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
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
    void healthyGrantsAreReusedAndRenewalKeepsOldLinksUntilTheirOriginalExpiry() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("article.md", "![image](image.png)");
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        Instant issued = clock.now;
        String original = articleToken(service, workspace, "/article", "image.png");
        clock.now = issued.plusSeconds(240);
        assertThat(articleToken(service, workspace, "/article", "image.png")).isEqualTo(original);
        clock.now = issued.plusSeconds(241);
        String renewed = articleToken(service, workspace, "/article", "image.png");
        assertThat(renewed).isNotEqualTo(original);
        clock.now = issued.plusSeconds(299);
        for (int i = 0; i < 130; i++)
            assertThat(articleToken(service, workspace, "/article", "image.png"))
                    .isEqualTo(renewed);
        assertThat(service.readPublicImage(workspace, original).bytes()).isEqualTo(png(1));
        clock.now = issued.plusSeconds(300);
        assertNotFound(() -> service.readPublicImage(workspace, original));
        assertThat(articleToken(service, workspace, "/article", "image.png")).isEqualTo(renewed);
        assertThat(service.readPublicImage(workspace, renewed).bytes()).isEqualTo(png(1));
        clock.now = issued.plusSeconds(541);
        assertNotFound(() -> service.readPublicImage(workspace, renewed));
    }

    @Test
    void snapshotExpiryRemainsTheLimitWhenRenewalCannotImproveTheLifetime() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("article.md", "![image](image.png)");
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofSeconds(10));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        String original = articleToken(service, workspace, "/article", "image.png");
        clock.now = clock.now.plusSeconds(9);
        assertThat(articleToken(service, workspace, "/article", "image.png")).isEqualTo(original);
        assertThat(service.readPublicImage(workspace, original).bytes()).isEqualTo(png(1));
        clock.now = clock.now.plusSeconds(1);
        assertNotFound(() -> service.readPublicImage(workspace, original));
        assertThatThrownBy(() -> service.publicDocument(workspace, "/article"))
                .isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void capacityOmitsOnlyNewImagesAndPreservesExistingGrantsAcrossWorkspaces(CapturedOutput output) throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("plain.md", "# Still readable");
        for (int i = 0; i < 128; i++) files.put("article-" + i + ".md", text("![image](image.png)"));
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        WorkspaceId other = WorkspaceId.random();
        var otherFiles = files("article.md", "# Other workspace\n![known](known.png)\n![new](new.png)");
        otherFiles.put("known.png", png(2));
        otherFiles.put("new.png", png(3));
        fixture.commitRemote(other, otherFiles);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        snapshots.refresh(other);
        var service = service(fixture, snapshots);
        String original = articleToken(service, workspace, "/article-0", "image.png");
        for (int i = 1; i < 127; i++) articleToken(service, workspace, "/article-" + i, "image.png");
        var partial = service.publicDocument(other, "/article").orElseThrow();
        assertThat(partial.media().body()).contains("# Other workspace", "![new](new.png)");
        assertThat(partial.media().images()).containsOnlyKeys("known.png");
        String otherGrant = token(partial.media().images().get("known.png"));
        for (int i = 0; i < 8; i++)
            assertThat(service.publicDocument(workspace, "/article-127")
                            .orElseThrow()
                            .media()
                            .images())
                    .isEmpty();
        assertThat(service.publicDocument(workspace, "/plain")
                        .orElseThrow()
                        .article()
                        .title())
                .isEqualTo("Still readable");
        assertThat(articleToken(service, workspace, "/article-0", "image.png")).isEqualTo(original);
        assertThat(service.readPublicImage(workspace, original).bytes()).isEqualTo(png(1));
        assertThat(service.readPublicImage(other, otherGrant).bytes()).isEqualTo(png(2));
        assertNotFound(() -> service.readPublicImage(other, original));
        assertNotFound(() -> service.readPublicImage(workspace, otherGrant));
        assertThat(output.getAll().lines().filter(line -> line.contains("Image grant capacity exhausted")))
                .hasSize(1);
        clock.now = clock.now.plusSeconds(60);
        assertThat(service.publicDocument(workspace, "/article-127")
                        .orElseThrow()
                        .media()
                        .images())
                .isEmpty();
        assertThat(output.getAll())
                .contains("omitted 9 image authorization(s)")
                .doesNotContain(workspace.toString(), other.toString(), original, otherGrant, "new.png");
        clock.now = clock.now.plusSeconds(240);
        assertNotFound(() -> service.readPublicImage(workspace, original));
        assertNotFound(() -> service.readPublicImage(other, otherGrant));
        assertThat(service.publicDocument(other, "/article")
                        .orElseThrow()
                        .media()
                        .images())
                .containsOnlyKeys("known.png", "new.png");
        assertThat(service.publicDocument(workspace, "/article-127")
                        .orElseThrow()
                        .media()
                        .images())
                .containsKey("image.png");
    }

    @Test
    void aBlockedCapacityWarningDoesNotBlockAnotherWorkspacesIssuedImage() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        var files = files("article-0.md", "![image](image.png)");
        for (int i = 1; i < 128; i++) files.put("article-" + i + ".md", text("![image](image.png)"));
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        WorkspaceId other = WorkspaceId.random();
        var otherFiles = files("article.md", "![other](image.png)");
        byte[] otherImage = png(2);
        otherFiles.put("image.png", otherImage);
        fixture.commitRemote(other, otherFiles);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        snapshots.refresh(other);
        var service = service(fixture, snapshots);
        String otherGrant = articleToken(service, other, "/article", "image.png");
        for (int i = 0; i < 127; i++) articleToken(service, workspace, "/article-" + i, "image.png");

        var logging = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (!event.getMessage().startsWith("Image grant capacity exhausted;")) return;
                logging.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("warning release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        };
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AssetService.class);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var warning = pool.submit(() -> service.publicDocument(workspace, "/article-127"));
            try {
                assertThat(logging.await(5, TimeUnit.SECONDS)).isTrue();
                var reading = pool.submit(() -> service.readPublicImage(other, otherGrant));
                assertThat(reading.get(5, TimeUnit.SECONDS).bytes()).isEqualTo(otherImage);
                assertThat(warning).isNotDone();
            } finally {
                release.countDown();
                assertThat(warning.get(5, TimeUnit.SECONDS)
                                .orElseThrow()
                                .media()
                                .images())
                        .isEmpty();
            }
        } finally {
            release.countDown();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void aWithdrawalAlreadyHoldingTheAuthorityLockPreventsNearExpiryRenewal() throws Exception {
        var delegate = new JGitRemoteGitTransport();
        AtomicBoolean pauseFetch = new AtomicBoolean();
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RemoteGitTransport transport = new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                if (pauseFetch.get()) {
                    fetching.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fetch release timed out");
                    } catch (InterruptedException interrupted) {
                        throw new RuntimeException(interrupted);
                    }
                }
                return delegate.fetchMain(repository, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                return delegate.pushMain(repository, binding, expected, candidate);
            }
        };
        var fixture = new RemoteRepositoryFixture(directory, transport, clock);
        var files = files("article.md", "![image](image.png)");
        files.put("image.png", png(1));
        fixture.commitRemote(workspace, files);
        var snapshots = snapshots(fixture, Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots);
        String original = articleToken(service, workspace, "/article", "image.png");
        clock.now = clock.now.plusSeconds(299);
        files.put(RepositoryPublishingPolicy.PATH, text("enabled: false\nmode: public-by-default\n"));
        fixture.commitRemote(workspace, files);
        pauseFetch.set(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var withdrawing = pool.submit(() -> snapshots.refresh(workspace));
            assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();
            var renewing = pool.submit(() -> service.publicDocument(workspace, "/article"));
            try {
                assertThatThrownBy(() -> renewing.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(withdrawing.get(5, TimeUnit.SECONDS).articles()).isEmpty();
            assertThat(renewing.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
        }
        assertThat(service.readPublicImage(workspace, original).bytes()).isEqualTo(png(1));
        clock.now = clock.now.plusSeconds(1);
        assertNotFound(() -> service.readPublicImage(workspace, original));
    }

    @Test
    void privatePreviewUsesRepositoryParserAndRechecksCurrentAuthorizationForEveryImage() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
        var fixture = new RemoteRepositoryFixture(directory, clock);
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
        var fixture = new RemoteRepositoryFixture(directory, clock);
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

    private static String articleToken(AssetService service, WorkspaceId workspace, String route, String image) {
        return token(service.publicDocument(workspace, route)
                .orElseThrow()
                .media()
                .images()
                .get(image));
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
        volatile Instant now = Instant.parse("2026-09-05T00:00:00Z");

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
