package io.github.core607.poketto.content;

import java.util.Map;
import java.util.Optional;

/** Remote acknowledgement and per-path revisions. Snapshot installation is a separate observation. */
public record RepositoryPatchResult(
        String commit, boolean committed, boolean snapshotUpdated, Map<String, Optional<DocumentRevision>> revisions) {
    public RepositoryPatchResult {
        revisions = Map.copyOf(revisions);
    }
}
