package io.github.core607.poketto.web.internal;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/public", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicDocumentController {
    private final PublicDocuments documents;

    PublicDocumentController(PublicDocuments documents) {
        this.documents = documents;
    }

    @GetMapping("/documents")
    ResponseEntity<PublicDocuments.Page> list(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return response(documents.search(query, tag, from, to, offset, limit));
    }

    @GetMapping("/document")
    ResponseEntity<PublicDocument> get(@RequestParam String route) {
        return response(documents.find(route));
    }

    @GetMapping("/tags")
    ResponseEntity<PublicDocuments.Tags> tags(
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return response(documents.tags(offset, limit));
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
