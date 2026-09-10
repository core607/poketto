package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Optional;
import java.util.UUID;

/** Private, disposable Git bundles for a configured worker staging directory; caller paths are never accepted. */
public interface RepositorySnapshotExports {
    Export create(AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit);

    /** Builds a fresh public reading baseline; original commits and configuration never enter its bundle. */
    PublicExport createPublic(AuthPrincipal actor, WorkspaceId workspace);

    /** Revalidates the current public projection; unrelated private changes do not revoke it. */
    void requireCurrentPublic(AuthPrincipal actor, WorkspaceId workspace, PublicExport exported);

    void release(UUID exportId);

    record Export(UUID exportId, String commit, String bundleSha256, long bundleBytes) {}

    /** Authority identity and source mapping stay with the host, outside the writable projection. */
    record PublicExport(
            WorkspaceId workspaceId,
            Export export,
            String authorityCommit,
            String projectionSha256,
            java.util.Map<String, String> sourcePaths,
            java.util.Map<String, PublicMedia> media) {
        public PublicExport {
            sourcePaths = java.util.Map.copyOf(sourcePaths);
            media = java.util.Map.copyOf(media);
            if (!sourcePaths.keySet().containsAll(media.keySet()))
                throw new IllegalArgumentException("public media requires a source mapping");
        }
    }

    record PublicMedia(String route, RepositoryMediaIndex.Media original) {}
}
