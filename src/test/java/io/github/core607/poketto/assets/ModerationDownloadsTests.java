package io.github.core607.poketto.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModerationDownloadsTests {
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final SitePolicyService policies = mock(SitePolicyService.class);
    private final PublicContentSnapshots snapshots = mock(PublicContentSnapshots.class);
    private final RepositoryBlobReader repository = mock(RepositoryBlobReader.class);
    private final ManagedBlobStore originals = mock(ManagedBlobStore.class);
    private final String commit = "a".repeat(40);
    private final AtomicBoolean administrator = new AtomicBoolean(true);
    private final ManagedAsset asset =
            new ManagedAsset(new ManagedAssetReference(UUID.randomUUID(), "b".repeat(64)), "application/pdf", 524288);
    private ModerationContent review;

    @BeforeEach
    void setup() {
        Instant now = Instant.now();
        var snapshot = new PublicContentSnapshot(
                workspace,
                Optional.of(commit),
                now,
                now.plusSeconds(300),
                List.of(new PublicArticle(
                        "public/note.md",
                        "/note",
                        "Note",
                        "[Source](source.pdf) [Private](../private/source.pdf)",
                        List.of(),
                        now,
                        now,
                        false,
                        "",
                        null,
                        false)));
        when(snapshots.withCurrent(eq(workspace), any())).thenAnswer(invocation -> {
            Function<PublicContentSnapshot, ?> action = invocation.getArgument(1);
            return action.apply(snapshot);
        });
        var entry = new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        var catalog = new RepositoryMediaIndex(
                Map.of("public/source.pdf", entry, "public/unreferenced.pdf", entry, "private/source.pdf", entry));
        when(repository.media(workspace, commit))
                .thenReturn(new RepositoryMediaSnapshot(
                        workspace, commit, catalog, Set.of("public/source.pdf", "public/unreferenced.pdf")));
        when(originals.describe(workspace, asset.reference())).thenReturn(asset);
        doAnswer(invocation -> {
                    if (!administrator.get()) {
                        throw new AuthException(AuthException.Code.DENIED);
                    }
                    return null;
                })
                .when(policies)
                .requireAdministrator(actor);
        var files = new MediaFileService(mock(AuthService.class), repository, snapshots, snapshots, () -> originals);
        review = new ModerationContent(policies, snapshots, mock(AssetService.class), files);
    }

    @Test
    void onlyCurrentReferencedPublicOriginalsCanBeDownloaded() {
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(2);
                    output.write(new byte[(int) asset.size()]);
                    return asset;
                })
                .when(originals)
                .copyTo(eq(workspace), eq(asset.reference()), any());
        var output = new ByteArrayOutputStream();
        review.download(actor, workspace, commit, "/note", "public/source.pdf").writeTo(output);
        assertThat(output.size()).isEqualTo(asset.size());
        assertThatThrownBy(() -> review.download(actor, workspace, commit, "/note", "private/source.pdf"))
                .isInstanceOf(AssetStorageException.class);
        assertThatThrownBy(() -> review.download(actor, workspace, commit, "/note", "public/unreferenced.pdf"))
                .isInstanceOf(AssetStorageException.class);
        assertThatThrownBy(() -> review.download(actor, workspace, "c".repeat(40), "/note", "public/source.pdf"))
                .isInstanceOf(AssetStorageException.class);
    }

    @Test
    void revokingAdministratorStopsAnAlreadyPreparedStreamingDownload() {
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(2);
                    output.write(new byte[262144]);
                    administrator.set(false);
                    output.write(new byte[262144]);
                    return asset;
                })
                .when(originals)
                .copyTo(eq(workspace), eq(asset.reference()), any());
        var download = review.download(actor, workspace, commit, "/note", "public/source.pdf");
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> download.writeTo(output)).isInstanceOf(AuthException.class);
        assertThat(output.size()).isEqualTo(262144);
        assertThatThrownBy(() -> review.download(actor, workspace, commit, "/note", "public/source.pdf"))
                .isInstanceOf(AuthException.class);
    }
}
