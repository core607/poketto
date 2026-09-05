package io.github.core607.poketto.assets;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.InputStream;

/**
 * Authoritative original-byte storage. Entrances must authorize the workspace and operation before
 * invoking this port; a reference is not an access grant. No method publishes, writes Git, or deletes
 * acknowledged originals. Relational catalog and image-delivery grants belong above this port.
 */
public interface ManagedBlobStore {
    int MAX_UPLOAD_BYTES = 16 * 1024 * 1024;

    /** The root must be application-owned, exclusively managed here, and outside disposable caches. */
    static ManagedBlobStore local(java.nio.file.Path root) {
        return new io.github.core607.poketto.assets.internal.LocalManagedBlobStore(root);
    }

    /**
     * Reads at most the byte limit plus one detection byte without closing the caller's stream.
     * A key is 16–128 ASCII letters, digits, hyphens or underscores. It is scoped to this workspace.
     * Identical retries return the same immutable reference, including after restart. Different
     * bytes conflict. A storage failure acknowledges nothing; callers may retry with the same key.
     */
    ManagedAsset upload(WorkspaceId workspace, String operationKey, InputStream original);

    /** Reads a bounded exact revision. Wrong workspace, identity, or revision returns NOT_FOUND. */
    ManagedImage read(WorkspaceId workspace, ManagedAssetReference reference);

    /** Lists only acknowledged uploads; scans at most 10,000 operation records and returns at most 100 items. */
    ManagedAssetPage list(WorkspaceId workspace, int offset, int limit);
}
