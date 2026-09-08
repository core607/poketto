package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Optional;

/** Repository primitives for already-authorized callers; these results include private content. */
public interface RepositoryContentReader {
    /** An absent commit selects remote main. An explicit commit must belong to its history. */
    RepositoryTree readTree(WorkspaceId workspaceId, Optional<String> commit);

    /**
     * Lists immediate children without reading blobs. Empty path selects root; an offset after zero
     * requires the commit returned by the first page. Missing directories return expected absence;
     * a non-directory path is invalid. Symlinks and submodules are listed but never traversed.
     */
    RepositoryDirectoryPage listDirectory(
            WorkspaceId workspaceId, Optional<String> commit, String path, int offset, int limit);

    /** Reads original committed text, including malformed Markdown, without consulting a worktree. */
    RepositoryFile getFile(WorkspaceId workspaceId, Optional<String> commit, String path);
}
