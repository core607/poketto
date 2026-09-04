package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** A structured tree is not a publication decision. Its documents may contain private material. */
public record RepositoryTree(
        WorkspaceId workspaceId,
        Optional<String> commit,
        List<RepositoryDocument> documents,
        List<RepositoryDiagnostic> diagnostics) {
    public RepositoryTree {
        documents = List.copyOf(documents);
        diagnostics = List.copyOf(diagnostics);
    }
}
