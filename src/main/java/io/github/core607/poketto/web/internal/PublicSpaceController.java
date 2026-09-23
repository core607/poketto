package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicSpaceController {
    private final WorkspacePublications publications;
    private final WorkspaceCatalog workspaces;
    private final PublicDocuments documents;

    PublicSpaceController(WorkspacePublications publications, WorkspaceCatalog workspaces, PublicDocuments documents) {
        this.publications = publications;
        this.workspaces = workspaces;
        this.documents = documents;
    }

    @GetMapping("/api/public/default-space")
    ResponseEntity<PublicSpace> defaultSpace() {
        String slug = publications.settings(workspaces.defaultWorkspace().id()).slug();
        return response(space(slug));
    }

    @GetMapping("/api/public/spaces/{slug}")
    ResponseEntity<PublicSpace> info(@PathVariable String slug) {
        return response(space(slug));
    }

    @GetMapping("/api/public/spaces/{slug}/documents")
    ResponseEntity<PublicDocuments.Page> articles(
            @PathVariable String slug,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        var publication = published(slug);
        return response(documents.search(publication.workspaceId(), query, tag, from, to, offset, limit));
    }

    @GetMapping("/api/public/spaces/{slug}/document")
    ResponseEntity<PublicDocument> article(@PathVariable String slug, @RequestParam String route) {
        return response(documents.find(published(slug).workspaceId(), route));
    }

    @GetMapping("/api/public/spaces/{slug}/tags")
    ResponseEntity<PublicDocuments.Tags> tags(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return response(documents.tags(published(slug).workspaceId(), offset, limit));
    }

    private PublicSpace space(String slug) {
        var publication = published(slug);
        return new PublicSpace(publication.slug(), publication.displayName(), publication.publicDescription());
    }

    private WorkspacePublications.Publication published(String slug) {
        return publications
                .findPublished(slug)
                .orElseThrow(() -> new PublicResourceNotFoundException("public space not found"));
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record PublicSpace(String slug, String displayName, String description) {}
}
