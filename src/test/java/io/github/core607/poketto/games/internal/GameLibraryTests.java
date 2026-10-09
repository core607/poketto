package io.github.core607.poketto.games.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GameLibraryTests {
    @Test
    void anonymousReadsNeverExecuteAndSnapshotReplacementImmediatelyRemovesTheMarker() {
        var workspace = WorkspaceId.random();
        var articleId = UUID.randomUUID();
        var publication =
                new WorkspacePublications.Publication(workspace, "street", "Street", true, true, "", "", false);
        var publications = mock(WorkspacePublications.class);
        when(publications.findPublished("street")).thenReturn(Optional.of(publication));
        when(publications.publishedAfter(any(), anyInt())).thenReturn(List.of(publication));
        var article = new PublicArticle(
                "public/game.md",
                "/game",
                "Game",
                "Intro",
                List.of(),
                Instant.now(),
                Instant.now(),
                false,
                "",
                articleId,
                false);
        var snapshots = new Snapshots();
        snapshots.value = snapshot(workspace, article, "a");
        var packages = mock(GamePackages.class);
        var bundle = new GameBundle(1, "export function init(){}", null, Map.of());
        when(packages.find(any(), any())).thenAnswer(call -> {
            assertThat(snapshots.reading).isFalse();
            return Optional.of(new GamePackages.Package("sha256:" + "a".repeat(64), "Help", bundle));
        });
        var runner = mock(GameRunner.class);
        when(runner.run(any(), any(), any())).thenAnswer(call -> {
            assertThat(snapshots.reading).isFalse();
            return new GameRunner.Result(
                    JsonMapper.shared().createObjectNode(),
                    new GameRunner.Observation("Ready", List.of(), false),
                    null);
        });
        try (var library = new DefaultGameLibrary(publications, snapshots, packages, runner, JsonMapper.shared())) {
            assertThat(library.find("street", articleId)).isEmpty();
            verifyNoInteractions(runner);
            library.refresh();
            assertThat(library.find("street", articleId)).isPresent();
            verify(runner).run(any(), any(), any());
            verifyNoMoreInteractions(runner);
            snapshots.value = snapshot(workspace, article, "b");
            assertThat(library.find("street", articleId)).isEmpty();
            library.refresh();
            assertThat(library.find("street", articleId)).isPresent();
            verifyNoMoreInteractions(runner);
            snapshots.value = new PublicContentSnapshot(
                    workspace,
                    Optional.of("b".repeat(40)),
                    Instant.now(),
                    Instant.now().plusSeconds(300),
                    List.of());
            assertThat(library.find("street", articleId)).isEmpty();
        }
    }

    private static PublicContentSnapshot snapshot(WorkspaceId workspace, PublicArticle article, String commit) {
        return new PublicContentSnapshot(
                workspace,
                Optional.of(commit.repeat(40)),
                Instant.now(),
                Instant.now().plusSeconds(300),
                List.of(article));
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private PublicContentSnapshot value;
        private boolean reading;

        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("Game catalogue never fetches Git");
        }

        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("Game catalogue never fetches Git");
        }

        public PublicContentSnapshot current(WorkspaceId workspace) {
            return value;
        }

        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            reading = true;
            try {
                return operation.apply(value);
            } finally {
                reading = false;
            }
        }
    }
}
