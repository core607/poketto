package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** Source metadata. Public scope follows the selected commit's policy; website delivery and caller grants remain separate. */
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
