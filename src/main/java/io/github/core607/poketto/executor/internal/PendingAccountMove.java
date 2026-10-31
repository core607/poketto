package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Retained move data remains readable independently of the transport that created it. */
final class PendingAccountMove {
    final RepositoryMoveRequest request;
    final byte[] payload;
    final Set<String> paths;
    RepositoryWriteAttempt attempt;
    RepositoryPatchResult result;
    Map<String, RetainedFileBaseline> fileBaselines = Map.of();

    PendingAccountMove(RepositoryMoveRequest request, byte[] payload, Set<String> paths) {
        this.request = Objects.requireNonNull(request, "Retained move request must be present");
        this.payload = payload.clone();
        this.paths = Set.copyOf(paths);
    }
}
