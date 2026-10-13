package io.github.core607.poketto.games;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.ArrayList;
import java.util.function.Function;

/** Adds the validated capability tag to an existing public view, without changing Git or publication authority. */
public final class GameContentSnapshots implements PublicContentSnapshots {
    private final PublicContentSnapshots snapshots;
    private final GameLibrary games;

    public GameContentSnapshots(PublicContentSnapshots snapshots, GameLibrary games) {
        this.snapshots = snapshots;
        this.games = games;
    }

    @Override
    public void ensureReady(WorkspaceId workspace) {
        snapshots.ensureReady(workspace);
    }

    @Override
    public PublicContentSnapshot refresh(WorkspaceId workspace) {
        snapshots.refresh(workspace);
        return current(workspace);
    }

    @Override
    public PublicContentSnapshot current(WorkspaceId workspace) {
        return withCurrent(workspace, Function.identity());
    }

    @Override
    public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> action) {
        return snapshots.withCurrent(workspace, snapshot -> action.apply(decorate(snapshot)));
    }

    private PublicContentSnapshot decorate(PublicContentSnapshot snapshot) {
        if (games == null) {
            return snapshot;
        }
        return new PublicContentSnapshot(
                snapshot.workspaceId(),
                snapshot.commit(),
                snapshot.verifiedAt(),
                snapshot.expiresAt(),
                snapshot.articles().stream()
                        .map(article -> article(article, snapshot, games))
                        .toList(),
                snapshot.collections(),
                snapshot.scheduled());
    }

    public static PublicArticle article(PublicArticle article, PublicContentSnapshot snapshot, GameLibrary games) {
        if (games == null
                || !games.recognizes(snapshot.workspaceId(), snapshot.commit().orElse(""), article.articleId())) {
            return article;
        }
        var tags = new ArrayList<>(article.tags());
        if (!tags.contains("小游戏")) {
            tags.add("小游戏");
        }
        return new PublicArticle(
                article.repositoryPath(),
                article.route(),
                article.title(),
                article.body(),
                tags,
                article.createdAt(),
                article.updatedAt(),
                article.folderPage(),
                article.publicAuthor(),
                article.articleId(),
                article.featured(),
                article.publishAt());
    }
}
