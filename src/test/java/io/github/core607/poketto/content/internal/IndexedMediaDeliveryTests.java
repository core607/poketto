package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.*;
import io.github.core607.poketto.auth.*;
import io.github.core607.poketto.content.*;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class IndexedMediaDeliveryTests {
    @TempDir
    Path directory;

    @Test
    void realGitIndexDrivesImagesAttachmentsGalleryHistoryAndWithdrawalWithoutPublishingPrivateAliases()
            throws Exception {
        var workspace = WorkspaceId.random();
        var fixture = new RemoteRepositoryFixture(directory.resolve("repository"));
        var store = ManagedBlobStore.local(directory.resolve("originals"));
        var imageOutput = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageOutput);
        byte[] image = imageOutput.toByteArray();
        var picture = store.uploadFile(workspace, "indexed-picture-01", "image/png", new ByteArrayInputStream(image));
        var mismatched =
                store.uploadFile(workspace, "mismatched-picture-01", "image/jpg", new ByteArrayInputStream(image));
        var file = store.uploadFile(
                workspace,
                "indexed-document-01",
                "application/pdf",
                new ByteArrayInputStream("PDF original".getBytes(StandardCharsets.UTF_8)));
        var index = new RepositoryMediaIndex(Map.of(
                "public/picture.png",
                indexed(picture),
                "public/gallery.png",
                indexed(picture),
                "public/bad.png",
                indexed(mismatched),
                "public/source.pdf",
                indexed(file),
                "private/picture.png",
                indexed(picture),
                "private/source.pdf",
                indexed(file)));
        String body =
                "# Note\n![Image](picture.png)\n![Hidden](../private/picture.png)\n[Source](source.pdf)\n[Hidden file](../private/source.pdf)";
        var files = new LinkedHashMap<String, byte[]>();
        files.put(RepositoryMediaIndex.PATH, index.encode());
        files.put(
                RepositoryPublishingPolicy.PATH, "enabled: true\nmode: public-root\n".getBytes(StandardCharsets.UTF_8));
        files.put("public/index.md", body.getBytes(StandardCharsets.UTF_8));
        files.put("public/oversized.png", new byte[(int) RepositoryBlobReader.MAX_BLOB_BYTES + 1]);
        var firstCommit = fixture.commitRemote(workspace, files);
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofMinutes(5));
        snapshots.refresh(workspace);
        var blobs = new JGitRepositoryBlobReader(fixture.authority());
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(actor.subjectId()).thenReturn(java.util.UUID.randomUUID());
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var admission = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 4, Duration.ZERO);
        var assets = new AssetService(
                auth,
                new JGitRepositoryContentReader(fixture.authority()),
                blobs,
                new RepositoryMarkdownConfiguration().repositoryMarkdownInspector(),
                snapshots,
                () -> store,
                directory.resolve("cache"),
                16 * 1024 * 1024,
                128,
                Clock.systemUTC(),
                admission);
        var page = assets.publicDocument(workspace, "/").orElseThrow();
        assertThat(page.media().images()).containsKey("picture.png").doesNotContainKey("../private/picture.png");
        assertThat(page.media().gallery()).hasSize(1);
        assertThat(page.media().galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.PARTIAL);
        assertThat(page.media().downloads()).containsKey("source.pdf").doesNotContainKey("../private/source.pdf");
        assertThat(page.media().links()).doesNotContainKey("source.pdf");
        assertThat(page.media().downloads().get("source.pdf"))
                .startsWith("/api/public/media?")
                .contains(firstCommit.name());
        var preview = assets.preview(actor, workspace, "public/index.md", body, Optional.of(firstCommit.name()));
        assertThat(preview.images()).containsKeys("picture.png", "../private/picture.png");
        assertThat(preview.downloads().get("../private/source.pdf")).startsWith("/api/admin/media?");
        String token = page.media().images().get("picture.png").substring("/api/public/assets/".length());
        assertThat(assets.readPublicImage(workspace, token).bytes()).isEqualTo(image);
        var media = new MediaFileService(auth, blobs, snapshots, () -> store);
        var downloaded = new ByteArrayOutputStream();
        media.publicDownload(workspace, firstCommit.name(), "/", "public/source.pdf")
                .writeTo(downloaded);
        assertThat(downloaded.toString(StandardCharsets.UTF_8)).isEqualTo("PDF original");
        files.put(
                RepositoryPublishingPolicy.PATH,
                "enabled: false\nmode: public-root\n".getBytes(StandardCharsets.UTF_8));
        fixture.commitRemote(workspace, files);
        snapshots.refresh(workspace);
        assertThatThrownBy(() -> assets.readPublicImage(workspace, token)).isInstanceOf(AssetStorageException.class);
        assertThatThrownBy(() -> media.publicDownload(workspace, firstCommit.name(), "/", "public/source.pdf"))
                .isInstanceOf(AssetStorageException.class);
        var lease = admission.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        try (var producer = lease.producer()) {
            assertThat(assets.readExact(
                                    actor,
                                    workspace,
                                    new AssetSource.Repository(Optional.of(firstCommit.name()), "private/picture.png"))
                            .bytes())
                    .isEqualTo(image);
        } finally {
            lease.responseComplete();
        }
    }

    private static RepositoryMediaIndex.Media indexed(ManagedAsset asset) {
        return new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
    }

    @Test
    void invalidMediaIndexKeepsIndependentlyAuthorizedGitImagesAndMarksGalleryIncomplete() throws Exception {
        var workspace = WorkspaceId.random();
        var fixture = new RemoteRepositoryFixture(directory.resolve("invalid-index"));
        var imageOutput = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageOutput);
        byte[] image = imageOutput.toByteArray();
        var files = new LinkedHashMap<String, byte[]>();
        files.put(
                RepositoryPublishingPolicy.PATH, "enabled: true\nmode: public-root\n".getBytes(StandardCharsets.UTF_8));
        files.put(RepositoryMediaIndex.PATH, "{broken".getBytes(StandardCharsets.UTF_8));
        files.put(
                "public/album/index.md",
                "# Album\n![Visible](photo.png)\n![Hidden](../../private/photo.png)\n![Missing](indexed.png)"
                        .getBytes(StandardCharsets.UTF_8));
        files.put("public/album/photo.png", image);
        files.put("public/album/gallery.png", image);
        files.put("private/photo.png", image);
        fixture.commitRemote(workspace, files);
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofMinutes(5));
        snapshots.refresh(workspace);
        var auth = mock(AuthService.class);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var admission = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 4, Duration.ZERO);
        var assets = new AssetService(
                auth,
                new JGitRepositoryContentReader(fixture.authority()),
                new JGitRepositoryBlobReader(fixture.authority()),
                new RepositoryMarkdownConfiguration().repositoryMarkdownInspector(),
                snapshots,
                () -> ManagedBlobStore.local(directory.resolve("unused-originals")),
                directory.resolve("git-cache"),
                16 * 1024 * 1024,
                128,
                Clock.systemUTC(),
                admission);
        var page = assets.publicDocument(workspace, "/album").orElseThrow();
        assertThat(page.media().images()).containsOnlyKeys("photo.png");
        assertThat(page.media().gallery()).hasSize(1);
        assertThat(page.media().galleryStatus()).isEqualTo(ResolvedMedia.GalleryStatus.PARTIAL);
        assertThat(page.media().downloads()).isEmpty();
        String imageUrl = page.media().images().get("photo.png");
        assertThat(assets.readPublicImage(workspace, imageUrl.substring("/api/public/assets/".length()))
                        .bytes())
                .isEqualTo(image);
        String galleryUrl = page.media().gallery().getFirst().src();
        assertThat(assets.readPublicImage(workspace, galleryUrl.substring("/api/public/assets/".length()))
                        .bytes())
                .isEqualTo(image);
        var lease = admission.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        try (var producer = lease.producer()) {
            assertThat(assets.readExact(
                                    mock(AuthPrincipal.class),
                                    workspace,
                                    new AssetSource.Repository(Optional.empty(), "private/photo.png"))
                            .bytes())
                    .isEqualTo(image);
        } finally {
            lease.responseComplete();
        }
    }
}
