package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.DocumentId;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only JSON entrance for the default workspace's public documents. A malformed, unknown, or
 * private id produces the same not-found response.
 */
@RestController
@RequestMapping(path = "/api/public/documents", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicDocumentController {

    private final PublicDocuments documents;

    PublicDocumentController(PublicDocuments documents) {
        this.documents = Objects.requireNonNull(documents, "public documents must not be null");
    }

    @GetMapping
    List<PublicDocumentSummary> list() {
        return documents.list().stream().map(PublicDocumentSummary::of).toList();
    }

    @GetMapping("/{documentId}")
    PublicDocument get(@PathVariable String documentId) {
        final DocumentId id;
        try {
            id = DocumentId.parse(documentId);
        } catch (IllegalArgumentException malformed) {
            throw notFound(documentId);
        }
        return documents.find(id).map(PublicDocument::of).orElseThrow(() -> notFound(documentId));
    }

    private static PublicResourceNotFoundException notFound(String documentId) {
        return new PublicResourceNotFoundException("no public document has id " + documentId);
    }
}
