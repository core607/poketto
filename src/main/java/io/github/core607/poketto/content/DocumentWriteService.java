package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;

/**
 * The single machine write path into a workspace content repository. MCP tools, the admin
 * interface, and projection replay share these commit semantics so that the acknowledgement point,
 * conflict behaviour, and audit attribution cannot diverge per entrance.
 *
 * <p>These operations do not authorize. An entry point resolves an authorized workspace and
 * capability first: mutating a private document requires {@code WRITE_PRIVATE}, while
 * {@link #publish} and every mutation of an already-public document require {@code PUBLISH}.
 *
 * <p>Every operation validates its input, builds against a resolved remote {@code main}, and
 * acknowledges only after an exact expected-ref update succeeds or a lost response is reconciled
 * to that candidate commit. A competing remote update raises {@link RepositoryConflictException};
 * an unverifiable lost response raises {@link RepositoryWriteAmbiguousException} and must not be
 * retried blindly.
 */
public interface DocumentWriteService {

    /**
     * Creates a private document at the drafted path with a service-assigned id.
     *
     * @throws DocumentConflictException if the path is taken after Unicode normalization and case
     *     folding, or if the assigned id already exists
     */
    DocumentWriteResult create(WorkspaceId workspaceId, WritePrincipal principal, DocumentDraft draft);

    /**
     * Replaces the document's title, tags, body, and path, preserving its id, creation time,
     * publication time, and visibility. A path change is a move, and a move is an edit. An update
     * that changes neither the bytes nor the path succeeds without a new commit.
     *
     * @throws DocumentNotFoundException if no document carries the id
     * @throws DocumentConflictException if the live revision differs from {@code expectedRevision},
     *     or if the target path is taken
     */
    DocumentWriteResult update(
            WorkspaceId workspaceId,
            WritePrincipal principal,
            DocumentId documentId,
            DocumentRevision expectedRevision,
            DocumentDraft draft);

    /**
     * Removes the document file.
     *
     * @throws DocumentNotFoundException if no document carries the id
     * @throws DocumentConflictException if the live revision differs from {@code expectedRevision}
     */
    DocumentWriteResult delete(
            WorkspaceId workspaceId,
            WritePrincipal principal,
            DocumentId documentId,
            DocumentRevision expectedRevision);

    /**
     * Makes the document public, setting its publication time on the first publish only.
     * Publishing an already-public document at its live revision succeeds without a new commit.
     * No operation returns a public document to private.
     *
     * @throws DocumentNotFoundException if no document carries the id
     * @throws DocumentConflictException if the live revision differs from {@code expectedRevision}
     */
    DocumentWriteResult publish(
            WorkspaceId workspaceId,
            WritePrincipal principal,
            DocumentId documentId,
            DocumentRevision expectedRevision);
}
