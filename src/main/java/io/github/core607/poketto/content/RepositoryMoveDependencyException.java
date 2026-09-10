package io.github.core607.poketto.content;

/** A proposed move introduces a public reference to content that cannot be published. */
public final class RepositoryMoveDependencyException extends IllegalArgumentException {
    public RepositoryMoveDependencyException() {
        super("move would leave a public document referencing private or unsupported content");
    }
}
