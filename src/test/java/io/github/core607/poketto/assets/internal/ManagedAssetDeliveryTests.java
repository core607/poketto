package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryMarkdownInspector;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class ManagedAssetDeliveryTests {
    @TempDir
    Path directory;

    @Test
    void indexValidationResolvesOnlyMatchingImmutableMetadataInItsWorkspace() {
        var store = ManagedBlobStore.local(directory.resolve("originals"));
        var workspace = WorkspaceId.random();
        var asset = store.uploadFile(
                workspace, "index-validation-01", "application/pdf", new ByteArrayInputStream(new byte[] {1, 2, 3}));
        var entry = new io.github.core607.poketto.content.RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        var validator = new AssetsConfiguration().repositoryMediaValidator(() -> store);
        validator.validate(workspace, List.of(entry));
        assertThatThrownBy(() -> validator.validate(WorkspaceId.random(), List.of(entry)))
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.NOT_FOUND));
        var wrongType = new io.github.core607.poketto.content.RepositoryMediaIndex.Media(
                entry.assetId(), entry.revision(), "text/html", entry.size());
        assertThatThrownBy(() -> validator.validate(workspace, List.of(wrongType)))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongSize = new io.github.core607.poketto.content.RepositoryMediaIndex.Media(
                entry.assetId(), entry.revision(), entry.mediaType(), entry.size() + 1);
        assertThatThrownBy(() -> validator.validate(workspace, List.of(wrongSize)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishedManagedReferenceReadsExactDurableOriginalWithoutGitMutation() throws Exception {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        WorkspaceId workspace = WorkspaceId.random();
        var store = ManagedBlobStore.local(directory.resolve("originals"));
        byte[] bytes = LocalManagedBlobStoreTests.image("png");
        var uploaded = store.upload(workspace, "managed-upload-01", new ByteArrayInputStream(bytes));
        String authored = uploaded.reference().toString();
        var snapshot = new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                now,
                now.plusSeconds(3600),
                List.of(new PublicArticle(
                        "article.md",
                        "/article",
                        "Article",
                        "![image](" + authored + ")",
                        List.of(),
                        now,
                        now,
                        false)));
        PublicContentSnapshots snapshots = mock(PublicContentSnapshots.class);
        when(snapshots.withCurrent(eq(workspace), any()))
                .thenAnswer(
                        invocation -> ((Function<PublicContentSnapshot, ?>) invocation.getArgument(1)).apply(snapshot));
        AuthService auth = mock(AuthService.class);
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        var blobs = mock(RepositoryBlobReader.class);
        AssetService service = new AssetService(
                auth,
                mock(RepositoryContentReader.class),
                blobs,
                mock(RepositoryMarkdownInspector.class),
                snapshots,
                () -> store,
                directory.resolve("cache"),
                16L * 1024 * 1024,
                128,
                Clock.fixed(now, ZoneOffset.UTC),
                new io.github.core607.poketto.assets.ImageMemoryAdmission(
                        256L * 1024 * 1024, 16, java.time.Duration.ZERO));
        var page = service.publicDocument(workspace, "/article").orElseThrow();
        String url = page.media().images().get(authored);
        assertThat(url).startsWith("/api/public/assets/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(service.readPublicImage(workspace, token).bytes()).isEqualTo(bytes);
        var actor = mock(AuthPrincipal.class);
        assertThat(service.readExact(actor, workspace, new AssetSource.Managed(uploaded.reference()))
                        .revision())
                .isEqualTo(uploaded.reference().revision());
        assertThatThrownBy(() -> service.readPublicImage(WorkspaceId.random(), token))
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(AssetStorageException.Reason.NOT_FOUND));
        verifyNoInteractions(blobs);
    }
}
