package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Already-authorized binary reads, with no checkout or Git mutation. Never expose this port as a raw public endpoint. */
public interface RepositoryBlobReader {
    int MAX_BLOB_BYTES = 16 * 1024 * 1024;

    /** Fetches main and validates a client-requested commit against its bounded history. */
    Optional<String> selectCommit(WorkspaceId workspace, Optional<String> requested);

    /** These cache-only methods require a server-selected commit, including a still-live earlier snapshot. */
    Optional<RepositoryBlob> find(WorkspaceId workspace, String commit, String path);

    List<RepositoryBlob> siblings(
            WorkspaceId workspace, String commit, String documentPath, int limit, boolean publicOnly);
    /** Lists at most 1000 bounded regular image candidates under a literal path prefix. */
    List<RepositoryBlob> images(WorkspaceId workspace, String commit, String prefix);

    /** Exact descriptor replay; does not reinterpret an earlier approved object under newer policy. */
    byte[] read(RepositoryBlob descriptor);

    /**
     * Verifies the exact source objects and prevents automatic source-cache eviction until expiry.
     * Requires an already-authorized descriptor and a future expiry at most five minutes away.
     * Does not fetch, publish, or rely on a hit in the derived image cache.
     */
    void protect(RepositoryBlob descriptor, Instant expiresAt);
}
