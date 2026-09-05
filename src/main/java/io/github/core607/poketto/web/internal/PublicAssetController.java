package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicAssetController {
    private final AssetService assets;
    private final WorkspaceCatalog workspaces;

    PublicAssetController(AssetService assets, WorkspaceCatalog workspaces) {
        this.assets = assets;
        this.workspaces = workspaces;
    }

    @GetMapping("/api/public/assets/{token}")
    ResponseEntity<byte[]> image(@PathVariable String token) {
        var image = assets.readPublicImage(workspaces.defaultWorkspace().id(), token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(image.bytes());
    }
}
