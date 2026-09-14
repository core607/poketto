package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.OutputStream;
import java.util.Optional;
import java.util.function.Consumer;

/** Repository primitives for already-authorized callers; these results include private content. */
public interface RepositoryContentReader {
    /** An absent commit selects remote main. An explicit commit must belong to its history. */
    RepositoryTree readTree(WorkspaceId workspaceId, Optional<String> commit);

    /**
     * Lists immediate Git and indexed-media children without reading original media bytes.
     * Empty path selects root; an offset after zero
     * requires the commit returned by the first page. Missing directories return expected absence;
     * a non-directory path is invalid. Symlinks and submodules are listed but never traversed.
     */
    RepositoryDirectoryPage listDirectory(
            WorkspaceId workspaceId, Optional<String> commit, String path, int offset, int limit);

    /** Searches regular Git and indexed-media paths without reading document or original bytes. */
    RepositoryFilenamePage searchFilenames(
            WorkspaceId workspace, Optional<String> commit, RepositoryFilenameSearch search);

    /** Reads committed text; indexed media reports MANAGED_MEDIA rather than absence or placeholder bytes. */
    RepositoryFile getFile(WorkspaceId workspaceId, Optional<String> commit, String path);

    RepositorySyncEntry inspectBlob(WorkspaceId workspace, String commit, String path);

    /** Streams exact committed bytes into caller-owned staging; no caller-visible publication happens here. */
    void copyBlob(WorkspaceId workspace, RepositorySyncEntry blob, OutputStream output);

    /**
     * Visits one pinned commit's Git entries (including directories) and indexed-media paths once.
     * Uses the same file semantics as getFile, without following links or loading original media.
     * The sink may stage partial private data; it must discard that data if this method fails.
     * Absence can be inferred only after successful completion, for valid file paths not visited.
     */
    void visitBaseline(
            WorkspaceId workspace, String commit, RepositoryBaselineLimits limits, Consumer<RepositoryFile> sink);

    /** Current publication-eligible source, independent of the workspace website switch; never historical content. */
    RepositoryTree readPublicTree(WorkspaceId workspaceId, Optional<String> commit);

    /** Filters private paths before pagination; a continuation must still name current remote main. */
    RepositoryDirectoryPage listPublicDirectory(
            WorkspaceId workspaceId, Optional<String> commit, String path, int offset, int limit);

    /** Publication-eligible current filenames, independent of the anonymous website switch. */
    RepositoryFilenamePage searchPublicFilenames(
            WorkspaceId workspace, Optional<String> commit, RepositoryFilenameSearch search);

    /** Reads eligible current source only, including expected absence for an eligible new path. */
    RepositoryFile getPublicFile(WorkspaceId workspaceId, Optional<String> commit, String path);
}
