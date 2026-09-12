package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/**
 * One file as of one commit, including the case where it is absent. An expected absence is
 * carried rather than signalled by a null source, because a writer needs to tell "this path
 * is known to be empty" from "this path was never read" when it checks its precondition.
 * Public scope follows the selected commit's policy; website delivery and caller grants remain separate.
 */
public record RepositoryFile(
        WorkspaceId workspaceId,
        Optional<String> commit,
        String path,
        boolean expectedAbsence,
        Optional<String> source,
        Optional<DocumentRevision> revision,
        List<RepositoryDiagnostic> diagnostics,
        boolean publicScope) {
    public RepositoryFile {
        diagnostics = List.copyOf(diagnostics);
        if (expectedAbsence && (source.isPresent() || revision.isPresent())) {
            throw new IllegalArgumentException("an absent path has no source or revision");
        }
    }
}
