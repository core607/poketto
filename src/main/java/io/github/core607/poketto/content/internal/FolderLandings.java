package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryDocument;
import java.util.HashSet;
import java.util.List;

/** Selection runs after scope filtering and parsing, before route collision checks. */
final class FolderLandings {
    private FolderLandings() {}

    static void preferIndex(List<RepositoryDocument> documents, List<RepositoryDiagnostic> diagnostics) {
        var indexes = new HashSet<String>();
        for (RepositoryDocument document : documents) {
            String path = document.file().path();
            if (path.equals("index.md") || path.endsWith("/index.md")) {
                indexes.add(path);
            }
        }
        documents.removeIf(document -> {
            String path = document.file().path();
            boolean readme = path.equals("README.md") || path.endsWith("/README.md");
            if (readme && indexes.contains(path.substring(0, path.length() - "README.md".length()) + "index.md")) {
                diagnostics.add(new RepositoryDiagnostic(
                        path,
                        "SHADOWED_FOLDER_LANDING",
                        "index.md owns this folder; README.md remains available as a raw file"));
                return true;
            }
            return false;
        });
    }
}
