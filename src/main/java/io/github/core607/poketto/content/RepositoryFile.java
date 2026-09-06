package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

public record RepositoryFile(
        WorkspaceId workspaceId,
        Optional<String> commit,
        String path,
        boolean expectedAbsence,
        Optional<String> source,
        Optional<DocumentRevision> revision,
        List<RepositoryDiagnostic> diagnostics) {
    public RepositoryFile {
        diagnostics = List.copyOf(diagnostics);
        if (expectedAbsence && (source.isPresent() || revision.isPresent())) {
            throw new IllegalArgumentException("an absent path has no source or revision");
        }
    }
}
