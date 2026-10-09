package io.github.core607.poketto.games.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GameCatalogueTests {
    @Test
    void unchangedInvalidRulesKeepAnAuthorReasonWithoutRepeatedExecution() {
        try (var fixture = new Fixture()) {
            when(fixture.runner.run(any(), any(), any())).thenThrow(new GameException("INVALID_GAME", "Bad rules"));
            fixture.library.refresh();
            fixture.library.refresh();
            assertThat(fixture.library.find(fixture.workspace, fixture.article.articleId()))
                    .isEmpty();
            assertThat(fixture.library.inspect(fixture.workspace).items())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.code()).isEqualTo("INVALID_GAME");
                        assertThat(item.articlePath()).isEqualTo("public/game.md");
                    });
            verify(fixture.runner).run(any(), any(), any());
            fixture.version = "sha256:" + "b".repeat(64);
            fixture.library.refresh();
            verify(fixture.runner, times(2)).run(any(), any(), any());
        }
    }

    @Test
    void knownGamesAreRecheckedBeforeNewArticlesAndClosedWebsitesReleaseCapacity() {
        try (var fixture = new Fixture()) {
            fixture.library.refresh();
            var articles = new ArrayList<PublicArticle>();
            for (int i = 0; i < 40; i++) {
                articles.add(fixture.article("public/ordinary-" + i + ".md"));
            }
            articles.add(fixture.article);
            fixture.snapshots.value = fixture.snapshot(articles, "b");
            assertThat(fixture.library.find(fixture.workspace, fixture.article.articleId()))
                    .isEmpty();
            fixture.library.refresh();
            assertThat(fixture.library.find(fixture.workspace, fixture.article.articleId()))
                    .isPresent();
            verify(fixture.runner).run(any(), any(), any());
            doThrow(new PublicationUnavailableException())
                    .when(fixture.publications)
                    .requireEnabled(fixture.workspace);
            fixture.library.refresh();
            assertThat(fixture.library.recognizes(fixture.workspace, "b".repeat(40), fixture.article.articleId()))
                    .isFalse();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final WorkspaceId workspace = WorkspaceId.random();
        private final PublicArticle article = article("public/game.md");
        private final WorkspacePublications publications = mock(WorkspacePublications.class);
        private final GameRunner runner = mock(GameRunner.class);
        private final Snapshots snapshots = new Snapshots();
        private final DefaultGameLibrary library;
        private String version = "sha256:" + "a".repeat(64);

        Fixture() {
            var publication =
                    new WorkspacePublications.Publication(workspace, "street", "Street", true, true, "", "", false);
            when(publications.publishedAfter(any(), anyInt())).thenReturn(List.of(publication));
            snapshots.value = snapshot(List.of(article), "a");
            var packages = mock(GamePackages.class);
            when(packages.find(any(), any()))
                    .thenAnswer(call -> article.equals(call.getArgument(1))
                            ? Optional.of(new GamePackages.Package(
                                    version, "Help", new GameBundle(1, "rules", null, Map.of())))
                            : Optional.empty());
            when(runner.run(any(), any(), any()))
                    .thenReturn(new GameRunner.Result(
                            JsonMapper.shared().createObjectNode(),
                            new GameRunner.Observation("Ready", List.of(), false),
                            null));
            library = new DefaultGameLibrary(publications, snapshots, packages, runner, JsonMapper.shared());
        }

        PublicArticle article(String path) {
            return new PublicArticle(
                    path,
                    "/game",
                    "Game",
                    "Intro",
                    List.of(),
                    Instant.now(),
                    Instant.now(),
                    false,
                    "",
                    UUID.randomUUID(),
                    false);
        }

        PublicContentSnapshot snapshot(List<PublicArticle> articles, String commit) {
            return new PublicContentSnapshot(
                    workspace,
                    Optional.of(commit.repeat(40)),
                    Instant.now(),
                    Instant.now().plusSeconds(300),
                    articles);
        }

        public void close() {
            library.close();
        }
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private PublicContentSnapshot value;

        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("Unexpected Git read");
        }

        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("Unexpected Git read");
        }

        public PublicContentSnapshot current(WorkspaceId workspace) {
            return value;
        }

        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            return operation.apply(value);
        }
    }
}
