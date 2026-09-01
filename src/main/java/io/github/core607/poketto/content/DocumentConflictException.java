package io.github.core607.poketto.content;

import java.util.Objects;
import java.util.Optional;

/**
 * Reports that a write was refused because the repository does not hold the state the caller
 * expected. Nothing changed, so a caller re-reads instead of retrying blind.
 *
 * <p>A revision mismatch carries the live revision. A path or document-id collision carries none,
 * because the conflict is with a different document.
 */
public final class DocumentConflictException extends RuntimeException {

    private final transient Optional<DocumentRevision> liveRevision;

    public DocumentConflictException(String message, DocumentRevision liveRevision) {
        super(message);
        this.liveRevision =
                Optional.of(Objects.requireNonNull(liveRevision, "live document revision must not be null"));
    }

    public DocumentConflictException(String message) {
        super(message);
        this.liveRevision = Optional.empty();
    }

    public Optional<DocumentRevision> liveRevision() {
        return liveRevision;
    }
}
