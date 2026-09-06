package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Optional;

/** Repository primitives for already-authorized callers; these results include private content. */
public interface RepositoryContentReader {
    /** An absent commit selects remote main. An explicit commit must belong to its history. */
    RepositoryTree readTree(WorkspaceId workspaceId, Optional<String> commit);

    /** Reads original committed text, including malformed Markdown, without consulting a worktree. */
    RepositoryFile getFile(WorkspaceId workspaceId, Optional<String> commit, String path);
}
