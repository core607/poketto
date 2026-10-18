package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class DiscoveryGameCardsTests {
    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void derivedGameTagDoesNotInvalidateTheSourceUsedForCovers() {
        var workspace = WorkspaceId.random();
        String commit = "a".repeat(40);
        Instant now = Instant.now();
        var article = new PublicArticle(
                "public/game.md",
                "/game",
                "Game",
                "A puzzle",
                List.of("puzzle"),
                now,
                now,
                false,
                "",
                UUID.randomUUID(),
                false);
        var snapshot =
                new PublicContentSnapshot(workspace, Optional.of(commit), now, now.plusSeconds(3600), List.of(article));
        var source = mock(PublicContentSnapshots.class);
        when(source.withCurrent(any(), any()))
                .thenAnswer(call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(snapshot));
        var publication = new WorkspacePublications.Publication(workspace, "space", "Space", true, true, "", "", false);
        var publications = mock(WorkspacePublications.class);
        when(publications.publishedAfter(any(), anyInt())).thenReturn(List.of(publication));
        when(publications.findPublished("space")).thenReturn(Optional.of(publication));
        var library = mock(GameLibrary.class);
        when(library.recognizes(workspace, commit, article.articleId())).thenReturn(true);
        ObjectProvider<GameLibrary> games = mock(ObjectProvider.class);
        when(games.getIfAvailable()).thenReturn(library);
        var blobs = mock(RepositoryBlobReader.class);
        when(blobs.media(workspace, commit))
                .thenReturn(new RepositoryMediaSnapshot(workspace, commit, RepositoryMediaIndex.empty(), Set.of()));
        PublicDiscovery discovery =
                new WebConfiguration().publicDiscovery(source, publications, assets(source, blobs, now), games);
        var first = discovery.page(null, null, 0, "小游戏");

        assertThat(first.items()).singleElement().satisfies(card -> {
            assertThat(card.route()).isEqualTo("/game");
            assertThat(card.tags()).containsExactly("puzzle", "小游戏");
            assertThat(card.cover()).isNull();
        });
        assertThat(article.tags()).containsExactly("puzzle");
        assertThat(discovery.page(first.batch(), null, 0, null)).isEqualTo(first);
        when(publications.findPublished("space")).thenReturn(Optional.empty());
        assertThat(discovery.page(first.batch(), null, 0, null).items()).isEmpty();
    }

    private AssetService assets(PublicContentSnapshots source, RepositoryBlobReader blobs, Instant now) {
        return new AssetService(
                null,
                null,
                blobs,
                null,
                source,
                () -> mock(ManagedBlobStore.class),
                directory.resolve("images"),
                16 * 1024 * 1024,
                128,
                Clock.fixed(now, ZoneOffset.UTC),
                new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 16, Duration.ZERO));
    }
}
