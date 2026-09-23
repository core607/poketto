package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.ManagedImage;
import io.github.core607.poketto.assets.PublicAlbumCover;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.content.SiblingImages;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicAlbumCoverTests {
    private static final Instant AT = Instant.parse("2026-09-14T00:00:00Z");

    @TempDir
    Path directory;

    @BeforeEach
    void canonicalStorageRoot() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    void publicGitSiblingProducesOneCurrentThumbnailGrant() throws Exception {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article("public/album/index.md", "/album", true, "# Album\n\n![Inline](inline.png)");
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        byte[] image = png();
        var candidate = blob(workspace, commit, "public/album/cover.png", image);
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(candidate), false));
        when(blobs.media(any(), any()))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));
        when(blobs.read(candidate)).thenReturn(image);

        var service = service(blobs, snapshots, mock(ManagedBlobStore.class));
        var cover = service.publicCovers(snapshot, List.of(article)).get(article.route());
        assertThat(cover).isNotNull();

        assertThat(cover.album()).isTrue();
        assertThat(cover.src()).startsWith("/api/public/assets/");
        verify(blobs).siblings(eq(workspace), eq(commit), eq(article.repositoryPath()), eq(8), eq(true), any());
        verify(blobs).protect(eq(candidate), any());

        String token = token(cover.src());
        var thumbnail = service.readPublicImage(workspace, token);
        assertThat(thumbnail.mediaType()).isEqualTo("image/jpeg");
        assertThat(thumbnail.source()).isEqualTo(new AssetSource.Repository(Optional.of(commit), candidate.path()));
    }

    @Test
    void publicIndexedSiblingUsesManagedBytesWithoutGitReadOrProtection() throws Exception {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article("public/album/index.md", "/album", true);
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), false));
        byte[] image = png();
        var reference = new ManagedAssetReference(UUID.randomUUID(), "a".repeat(64));
        var media =
                new RepositoryMediaIndex.Media(reference.assetId(), reference.revision(), "image/png", image.length);
        String path = "public/album/cover.png";
        String inline = "public/album/inline.png";
        String nested = "public/album/nested/ignored.png";
        var catalog = new RepositoryMediaSnapshot(
                workspace,
                commit,
                new RepositoryMediaIndex(
                        Map.of(path, media, inline, media, nested, media, "private/secret.png", media)),
                Set.of(path, inline, nested));
        when(blobs.media(workspace, commit)).thenReturn(catalog);
        var originals = mock(ManagedBlobStore.class);
        when(originals.read(workspace, reference))
                .thenReturn(new ManagedImage(new ManagedAsset(reference, "image/png", image.length), image));

        var service = service(blobs, snapshots, originals);
        var cover = service.publicCovers(snapshot, List.of(article)).get(article.route());
        assertThat(cover).isNotNull();

        assertThat(cover.album()).isTrue();
        assertThat(cover.src()).startsWith("/api/public/assets/");
        verify(blobs, never()).read(any());
        verify(blobs, never()).protect(any(), any());
        assertThat(service.readPublicImage(token(cover.src())).mediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void changedPublicationAfterPreparationDoesNotReceiveAThumbnailGrant() throws Exception {
        var workspace = WorkspaceId.random();
        String selectedCommit = "b".repeat(40);
        var article = article("public/album/index.md", "/album", true);
        var selected = snapshot(workspace, selectedCommit, article);
        var changed = snapshot(workspace, "c".repeat(40), article);
        var snapshots = mock(PublicContentSnapshots.class);
        AtomicInteger calls = new AtomicInteger();
        when(snapshots.withCurrent(any(), any())).thenAnswer(call -> {
            var current = calls.getAndIncrement() == 0 ? selected : changed;
            return ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(current);
        });
        var blobs = mock(RepositoryBlobReader.class);
        byte[] image = png();
        var candidate = blob(workspace, selectedCommit, "public/album/cover.png", image);
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(candidate), false));
        when(blobs.media(any(), any()))
                .thenReturn(
                        new RepositoryMediaSnapshot(workspace, selectedCommit, RepositoryMediaIndex.empty(), Set.of()));
        when(blobs.read(candidate)).thenReturn(image);

        var service = service(blobs, snapshots, mock(ManagedBlobStore.class));
        assertThat(service.publicCovers(selected, List.of(article))).isEmpty();

        verify(blobs).read(candidate);
        verify(blobs, never()).protect(any(), any());
    }

    @Test
    void knownImageBudgetStopsAfterTwoMaximumGitCandidates() throws Exception {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article("public/album/index.md", "/album", true);
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        var candidates = List.of(
                oversizedBlob(workspace, commit, "public/album/a.png", "a".repeat(40)),
                oversizedBlob(workspace, commit, "public/album/b.png", "b".repeat(40)),
                oversizedBlob(workspace, commit, "public/album/c.png", "c".repeat(40)));
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(candidates, false));
        when(blobs.media(any(), any()))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));
        when(blobs.read(any())).thenThrow(new ContentRepositoryException("source unavailable"));

        var service = service(blobs, snapshots, mock(ManagedBlobStore.class));
        var cover = service.publicCovers(snapshot, List.of(article)).get(article.route());
        assertThat(cover).isNotNull();

        assertThat(cover.album()).isTrue();
        assertThat(cover.src()).isNull();
        verify(blobs, times(2)).read(any());
        verify(blobs, never()).protect(any(), any());
    }

    @Test
    void articleCoverUsesTheFirstPublicInlineImageWithoutScanningSiblings() throws Exception {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article(
                "public/notes/article.md",
                "/notes/article",
                false,
                "# Article\n\n![Hidden](../../private/secret.png)\n\n![Shown](first.png)\n\n![Later](later.png)");
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        byte[] image = png();
        var hidden = new RepositoryBlob(
                workspace,
                commit,
                "private/secret.png",
                blob(workspace, commit, "x", image).objectId(),
                image.length,
                false);
        var shown = blob(workspace, commit, "public/notes/first.png", image);
        when(blobs.find(workspace, commit, "private/secret.png")).thenReturn(Optional.of(hidden));
        when(blobs.find(workspace, commit, "public/notes/first.png")).thenReturn(Optional.of(shown));
        when(blobs.media(any(), any()))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));
        when(blobs.read(shown)).thenReturn(image);

        var service = service(blobs, snapshots, mock(ManagedBlobStore.class));
        var cover = service.publicCovers(snapshot, List.of(article)).get(article.route());

        assertThat(cover).isNotNull();
        assertThat(cover.album()).isFalse();
        assertThat(cover.src()).startsWith("/api/public/assets/");
        assertThat(service.readPublicImage(workspace, token(cover.src())).source())
                .isEqualTo(new AssetSource.Repository(Optional.of(commit), shown.path()));
        verify(blobs, org.mockito.Mockito.never()).siblings(any(), any(), any(), anyInt(), anyBoolean(), any());
        verify(blobs, org.mockito.Mockito.never()).read(hidden);
    }

    @Test
    void folderPageWithoutFurtherImagesUsesItsInlineFigure() throws Exception {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article("public/news/index.md", "/news", true, "# News\n\n![Figure](figure.png)");
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        byte[] image = png();
        var figure = blob(workspace, commit, "public/news/figure.png", image);
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), false));
        when(blobs.find(workspace, commit, figure.path())).thenReturn(Optional.of(figure));
        when(blobs.media(any(), any()))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));
        when(blobs.read(figure)).thenReturn(image);

        var service = service(blobs, snapshots, mock(ManagedBlobStore.class));
        var cover = service.publicCovers(snapshot, List.of(article)).get(article.route());

        assertThat(cover).isNotNull();
        assertThat(cover.album()).isFalse();
        assertThat(cover.src()).startsWith("/api/public/assets/");
        assertThat(service.readPublicImage(workspace, token(cover.src())).source())
                .isEqualTo(new AssetSource.Repository(Optional.of(commit), figure.path()));
        verify(blobs).siblings(eq(workspace), eq(commit), eq(article.repositoryPath()), eq(8), eq(true), any());
    }

    @Test
    void folderPageWithAnUnreadableInventoryIsNotTreatedAsADirectory() throws Exception {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article("public/trip/index.md", "/trip", true, "# Trip\n\n![Figure](figure.png)");
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        byte[] image = png();
        var figure = blob(workspace, commit, "public/trip/figure.png", image);
        var empty = new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of());

        var unlisted = mock(RepositoryBlobReader.class);
        when(unlisted.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenThrow(new ContentRepositoryException("listing unavailable"));
        when(unlisted.media(any(), any())).thenReturn(empty);
        var truncated = mock(RepositoryBlobReader.class);
        when(truncated.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), true));
        when(truncated.media(any(), any())).thenReturn(empty);
        var unindexed = mock(RepositoryBlobReader.class);
        when(unindexed.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), false));
        when(unindexed.media(any(), any())).thenThrow(new ContentRepositoryException("index unavailable"));

        for (var blobs : List.of(unlisted, truncated, unindexed)) {
            when(blobs.find(workspace, commit, figure.path())).thenReturn(Optional.of(figure));
            when(blobs.read(figure)).thenReturn(image);
            var service = service(blobs, snapshots, mock(ManagedBlobStore.class));

            assertThat(service.publicCovers(snapshot, List.of(article)).get(article.route()))
                    .isEqualTo(new PublicAlbumCover(false, null));
            verify(blobs, never()).find(any(), any(), any());
        }
    }

    @Test
    void articleWithoutPublicImagesHasNoCover() {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var article = article("public/article.md", "/article", false, "# Text only");
        var snapshot = snapshot(workspace, commit, article);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        when(blobs.media(any(), any()))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));

        var cover = service(blobs, snapshots, mock(ManagedBlobStore.class))
                .publicCovers(snapshot, List.of(article))
                .get(article.route());

        assertThat(cover).isEqualTo(new PublicAlbumCover(false, null));
    }

    @Test
    void sameWorkspaceCoverBatchReadsTheMediaCatalogOnlyOnce() {
        var workspace = WorkspaceId.random();
        String commit = "b".repeat(40);
        var articles = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> article("public/album-" + index + "/index.md", "/album-" + index, true))
                .toList();
        var snapshot = new PublicContentSnapshot(workspace, Optional.of(commit), AT, AT.plusSeconds(3600), articles);
        var snapshots = mock(PublicContentSnapshots.class);
        install(snapshots, snapshot);
        var blobs = mock(RepositoryBlobReader.class);
        when(blobs.siblings(any(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new SiblingImages(List.of(), false));
        when(blobs.media(workspace, commit))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));

        var covers = service(blobs, snapshots, mock(ManagedBlobStore.class)).publicCovers(snapshot, articles);

        assertThat(covers).hasSize(6).containsKeys("/album-0", "/album-5");
        verify(blobs, times(1)).media(workspace, commit);
        verify(blobs, times(6)).siblings(any(), any(), any(), eq(8), eq(true), any());
    }

    private AssetService service(
            RepositoryBlobReader blobs, PublicContentSnapshots snapshots, ManagedBlobStore originals) {
        return new AssetService(
                null,
                null,
                blobs,
                null,
                snapshots,
                () -> originals,
                directory.resolve("cache").toAbsolutePath(),
                16L * 1024 * 1024,
                128,
                Clock.fixed(AT, ZoneOffset.UTC),
                new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 16, Duration.ZERO));
    }

    private static PublicArticle article(String path, String route, boolean folderPage) {
        return article(path, route, folderPage, "# Album");
    }

    private static PublicArticle article(String path, String route, boolean folderPage, String body) {
        return new PublicArticle(path, route, "Album", body, List.of(), AT, AT, folderPage, "", null, false);
    }

    private static PublicContentSnapshot snapshot(WorkspaceId workspace, String commit, PublicArticle article) {
        return new PublicContentSnapshot(workspace, Optional.of(commit), AT, AT.plusSeconds(3600), List.of(article));
    }

    private static void install(PublicContentSnapshots snapshots, PublicContentSnapshot value) {
        when(snapshots.withCurrent(any(), any()))
                .thenAnswer(call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(value));
    }

    private static RepositoryBlob blob(WorkspaceId workspace, String commit, String path, byte[] bytes) {
        try (var formatter = new ObjectInserter.Formatter()) {
            return new RepositoryBlob(
                    workspace,
                    commit,
                    path,
                    formatter.idFor(Constants.OBJ_BLOB, bytes).name(),
                    bytes.length,
                    true);
        }
    }

    private static RepositoryBlob oversizedBlob(WorkspaceId workspace, String commit, String path, String objectId) {
        return new RepositoryBlob(workspace, commit, path, objectId, RepositoryBlobReader.MAX_BLOB_BYTES, true);
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
