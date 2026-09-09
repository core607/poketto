package io.github.core607.poketto.content;

import io.github.core607.poketto.content.internal.RepositoryPathRules;

/** Exact source and destination paths at one expected authority commit; folders are never merged. */
public record RepositoryMoveRequest(String baseCommit, String source, String destination) {
    public RepositoryMoveRequest {
        if (baseCommit == null || !baseCommit.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("move requires an exact base commit");
        source = RepositoryPathRules.validate(source);
        destination = RepositoryPathRules.validate(destination);
        if (RepositoryPathRules.reserved(source)
                || RepositoryPathRules.reserved(destination)
                || source.equalsIgnoreCase("public")
                || source.equalsIgnoreCase("private")
                || destination.equalsIgnoreCase("public")
                || destination.equalsIgnoreCase("private"))
            throw new IllegalArgumentException("move cannot replace repository roots or metadata");
        if (source.equals(destination) || destination.startsWith(source + "/") || source.startsWith(destination + "/"))
            throw new IllegalArgumentException("move paths must be distinct and must not contain each other");
    }
}
