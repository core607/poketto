package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** Already-authorized binary reads, with no checkout or Git mutation. Never expose this port as a raw public endpoint. */
public interface RepositoryBlobReader {
    int MAX_BLOB_BYTES = 16 * 1024 * 1024;

    /** Fetches main and validates a client-requested commit against its bounded history. */
    Optional<String> selectCommit(WorkspaceId workspace, Optional<String> requested);

    /** These cache-only methods require a server-selected commit, including a still-live earlier snapshot. */
    Optional<RepositoryBlob> find(WorkspaceId workspace, String commit, String path);

    /**
     * Non-recursive Java filename prefix. Normalized inline paths and public-policy exclusions do
     * not consume the 1–128 candidate limit. Oversized candidates mark partial without replacement;
     * an absent folder returns empty and complete. Storage or scan-bound failures throw.
     */
    SiblingImages siblings(
            WorkspaceId workspace,
            String commit,
            String documentPath,
            int limit,
            boolean publicOnly,
            java.util.Set<String> inlinePaths);
    /** Lists at most 1000 bounded regular image candidates under a literal path prefix. */
    List<RepositoryBlob> images(WorkspaceId workspace, String commit, String prefix);

    /** Exact descriptor replay; does not reinterpret an earlier approved object under newer policy. */
    byte[] read(RepositoryBlob descriptor);
}
