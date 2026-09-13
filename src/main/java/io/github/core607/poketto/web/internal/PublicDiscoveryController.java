package io.github.core607.poketto.web.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicDiscoveryController {
    private final PublicDiscovery discovery;

    PublicDiscoveryController(PublicDiscovery discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/api/public/discovery")
    ResponseEntity<PublicDiscovery.Page> discover(
            @RequestParam(required = false) String batch,
            @RequestParam(required = false) String afterBatch,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(discovery.page(batch, afterBatch, offset));
    }
}
