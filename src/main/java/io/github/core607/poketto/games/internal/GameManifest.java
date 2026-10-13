package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.games.GameBundle;
import java.util.List;
import java.util.UUID;

record GameManifest(
        int protocol, UUID articleId, String entry, String presentation, String help, List<String> resources) {
    GameManifest {
        if (protocol != 1 || articleId == null) {
            throw new IllegalArgumentException("Game manifest requires protocol 1 and its article ID");
        }
        entry = path(entry);
        if (presentation != null) {
            presentation = path(presentation);
        }
        GameBundle.text(help, 4096, "Game help");
        if (help.isBlank()) {
            throw new IllegalArgumentException("Game help cannot be empty");
        }
        if (resources == null || resources.size() > 32) {
            throw new IllegalArgumentException("Game resources must be a list of at most 32 paths");
        }
        resources = resources.stream().map(GameManifest::path).toList();
        if (resources.stream().distinct().count() != resources.size()) {
            throw new IllegalArgumentException("Game resource paths cannot repeat");
        }
    }

    private static String path(String path) {
        GameBundle.text(path, 256, "Package path");
        RepositoryPaths.validate(path);
        if (List.of(path.split("/")).stream().anyMatch(part -> part.startsWith("."))) {
            throw new IllegalArgumentException("Game package paths cannot include hidden segments");
        }
        return path;
    }
}
