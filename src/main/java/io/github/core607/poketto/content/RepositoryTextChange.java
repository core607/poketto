package io.github.core607.poketto.content;

import java.util.Objects;
import java.util.Optional;

/** An absent content value deletes a file; expected absence is an explicit creation precondition. */
public record RepositoryTextChange(
        String path, boolean expectedAbsence, Optional<DocumentRevision> expectedRevision, Optional<String> content) {
    public RepositoryTextChange {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(expectedRevision, "expected revision must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (expectedAbsence == expectedRevision.isPresent()) {
            throw new IllegalArgumentException("provide exactly one of expected absence or expected revision");
        }
        if (expectedAbsence && content.isEmpty()) {
            throw new IllegalArgumentException("an absent file cannot be deleted");
        }
    }
}
