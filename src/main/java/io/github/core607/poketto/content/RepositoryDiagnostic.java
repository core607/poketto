package io.github.core607.poketto.content;

/** Workspace-private diagnostic; never include it in a public response. */
public record RepositoryDiagnostic(String path, String code, String message) {}
