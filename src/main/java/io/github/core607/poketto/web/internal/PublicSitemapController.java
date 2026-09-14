package io.github.core607.poketto.web.internal;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicSitemapController {
    private final PublicSitemaps sitemaps;

    PublicSitemapController(PublicSitemaps sitemaps) {
        this.sitemaps = sitemaps;
    }

    @GetMapping("/api/public/sitemap")
    ResponseEntity<List<String>> spaces() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sitemaps.spaces());
    }

    @GetMapping("/api/public/spaces/{slug}/sitemap")
    ResponseEntity<PublicSitemaps.Space> space(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sitemaps.space(slug));
    }
}
