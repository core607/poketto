package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Optional;
import java.util.UUID;

/** Private, disposable Git bundles for a configured worker staging directory; caller paths are never accepted. */
public interface RepositorySnapshotExports {
    Export create(AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit);

    void release(UUID exportId);

    record Export(UUID exportId, String commit, String bundleSha256, long bundleBytes) {}
}
