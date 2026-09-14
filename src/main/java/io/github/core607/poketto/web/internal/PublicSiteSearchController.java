package io.github.core607.poketto.web.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicSiteSearchController {
    private final PublicSiteSearch search;

    PublicSiteSearchController(PublicSiteSearch search) {
        this.search = search;
    }

    @GetMapping("/api/public/search")
    ResponseEntity<PublicSiteSearch.Page> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "12") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(query, offset, limit));
    }
}
