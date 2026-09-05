package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;

/** Server-selected immutable Git object descriptor. Path eligibility alone is not an image-delivery grant. */
public record RepositoryBlob(
        WorkspaceId workspaceId, String commit, String path, String objectId, long size, boolean publicPath) {
    public RepositoryBlob {
        java.util.Objects.requireNonNull(workspaceId);
        if (commit == null
                || !commit.matches("[0-9a-f]{40}")
                || objectId == null
                || !objectId.matches("[0-9a-f]{40}")
                || path == null
                || path.isEmpty()
                || path.length() > ContentLimits.MAX_PATH_LENGTH
                || size < 0
                || size > RepositoryBlobReader.MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("invalid repository blob descriptor");
        }
    }
}
