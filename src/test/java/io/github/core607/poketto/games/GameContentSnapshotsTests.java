package io.github.core607.poketto.games;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GameContentSnapshotsTests {
    @Test
    @SuppressWarnings("unchecked")
    void markerUsesOnlyTheAuthorizedCurrentCommitWithoutAnotherGuardOrJob() {
        var workspace = WorkspaceId.random();
        var article = new PublicArticle(
                "public/game.md",
                "/game",
                "Game",
                "Intro",
                List.of("puzzle"),
                Instant.now(),
                Instant.now(),
                false,
                "",
                UUID.randomUUID(),
                false);
        var snapshot = new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                Instant.now(),
                Instant.now().plusSeconds(300),
                List.of(article));
        var source = mock(PublicContentSnapshots.class);
        when(source.withCurrent(any(), any()))
                .thenAnswer(call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(snapshot));
        var library = mock(GameLibrary.class);
        when(library.recognizes(workspace, "a".repeat(40), article.articleId())).thenReturn(true);
        var decorated = new GameContentSnapshots(source, library);
        assertThat(decorated.current(workspace).articles().getFirst().tags()).containsExactly("puzzle", "小游戏");
        assertThat(snapshot.articles().getFirst().tags()).containsExactly("puzzle");
        verify(library).recognizes(workspace, "a".repeat(40), article.articleId());
        verifyNoMoreInteractions(library);
        doThrow(new ContentRepositoryException("Withdrawn")).when(source).withCurrent(any(), any());
        assertThatThrownBy(() -> decorated.current(workspace)).isInstanceOf(ContentRepositoryException.class);
        verifyNoMoreInteractions(library);
    }
}
