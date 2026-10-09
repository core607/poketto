package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded diagnostic metadata also prevents repeatedly executing an unchanged invalid rule package. */
final class GameChecks {
    private final LinkedHashMap<Key, Check> values = new LinkedHashMap<>();

    synchronized void record(
            PublicContentSnapshot snapshot, PublicArticle article, String version, String code, String detail) {
        var key = new Key(snapshot.workspaceId(), article.repositoryPath());
        values.remove(key);
        values.put(
                key,
                new Check(
                        snapshot.commit().orElse(""),
                        version,
                        new GameLibrary.Diagnostic(article.repositoryPath(), article.articleId(), code, detail)));
        while (values.size() > 256) {
            values.pollFirstEntry();
        }
    }

    synchronized boolean rejected(WorkspaceId workspace, String path, String version) {
        Check check = values.get(new Key(workspace, path));
        return check != null
                && Objects.equals(check.version(), version)
                && check.diagnostic().code().equals("INVALID_GAME");
    }

    synchronized void forget(WorkspaceId workspace, String path) {
        values.remove(new Key(workspace, path));
    }

    synchronized void retainWorkspaces(Set<WorkspaceId> workspaces) {
        values.keySet().removeIf(key -> !workspaces.contains(key.workspace()));
    }

    synchronized Set<WorkspaceId> workspaces() {
        return values.keySet().stream().map(Key::workspace).collect(Collectors.toSet());
    }

    synchronized GameLibrary.Inspection inspect(PublicContentSnapshot snapshot) {
        Set<String> paths =
                snapshot.articles().stream().map(PublicArticle::repositoryPath).collect(Collectors.toSet());
        String commit = snapshot.commit().orElse("");
        List<GameLibrary.Diagnostic> items = values.entrySet().stream()
                .filter(item -> item.getKey().workspace().equals(snapshot.workspaceId()))
                .filter(item -> paths.contains(item.getKey().path())
                        && item.getValue().commit().equals(commit))
                .map(item -> item.getValue().diagnostic())
                .toList();
        return new GameLibrary.Inspection(commit, items);
    }

    private record Key(WorkspaceId workspace, String path) {}

    private record Check(String commit, String version, GameLibrary.Diagnostic diagnostic) {}
}
