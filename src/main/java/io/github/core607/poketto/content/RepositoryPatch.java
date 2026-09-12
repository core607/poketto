package io.github.core607.poketto.content;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Empty base commit denotes an unborn remote main, not an instruction to choose a current base. */
public record RepositoryPatch(Optional<String> baseCommit, List<RepositoryTextChange> changes) {
    /**
     * Paths one atomic patch may touch. The bound exists so a single acknowledged write
     * stays reviewable and replayable; the repository authoring record owns the value.
     */
    public static final int MAX_CHANGES = 64;

    /** Replacement text one patch may carry across all of its changes. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    public RepositoryPatch {
        Objects.requireNonNull(baseCommit, "base commit must not be null");
        changes = List.copyOf(changes);
        if (changes.isEmpty() || changes.size() > MAX_CHANGES) {
            throw new IllegalArgumentException("patch must contain between 1 and 64 changes");
        }
        if (baseCommit.isPresent() && !baseCommit.orElseThrow().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("base commit must be an exact lowercase Git commit id");
        }
    }
}
