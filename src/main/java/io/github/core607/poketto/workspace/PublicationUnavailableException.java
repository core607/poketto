package io.github.core607.poketto.workspace;

/** Missing and disabled websites share one failure without disclosing catalog metadata. */
public final class PublicationUnavailableException extends RuntimeException {
    public PublicationUnavailableException() {
        super("Workspace website is unavailable");
    }
}
