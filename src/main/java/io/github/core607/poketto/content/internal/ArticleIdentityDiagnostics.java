package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ArticleIdentityDiagnostics {
    private ArticleIdentityDiagnostics() {}

    static void append(List<RepositoryDocument> documents, List<RepositoryDiagnostic> diagnostics) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (RepositoryDocument document : documents) {
            if (document.articleId() != null) {
                counts.merge(document.articleId(), 1, Integer::sum);
            }
        }
        for (RepositoryDocument document : documents) {
            if (document.articleId() != null && counts.get(document.articleId()) > 1) {
                diagnostics.add(new RepositoryDiagnostic(
                        document.file().path(),
                        "DUPLICATE_ARTICLE_ID",
                        "article id is shared by multiple readable files; copied articles need different ids"));
            }
        }
    }
}
