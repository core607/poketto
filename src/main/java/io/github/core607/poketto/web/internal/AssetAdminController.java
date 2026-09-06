package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetPage;
import io.github.core607.poketto.assets.RepositoryImagePage;
import io.github.core607.poketto.assets.ResolvedMedia;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.io.IOException;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class AssetAdminController {
    private final AssetService assets;
    private final WorkspaceCatalog workspaces;

    AssetAdminController(AssetService assets, WorkspaceCatalog workspaces) {
        this.assets = assets;
        this.workspaces = workspaces;
    }

    @GetMapping("/assets")
    ManagedAssetPage list(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return assets.list(actor, workspaces.defaultWorkspace().id(), offset, limit);
    }

    @PostMapping(path = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ManagedAsset upload(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestPart("file") MultipartFile file) {
        try (var input = file.getInputStream()) {
            return assets.upload(actor, workspaces.defaultWorkspace().id(), operationKey, input);
        } catch (IOException exception) {
            throw new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
        }
    }

    @GetMapping("/assets/repository")
    RepositoryImagePage repository(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(required = false) String commit,
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return assets.repositoryImages(
                actor, workspaces.defaultWorkspace().id(), Optional.ofNullable(commit), prefix, offset, limit);
    }

    @PostMapping("/repository/preview")
    ResolvedMedia preview(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody Preview request) {
        if (request.path() == null || request.body() == null)
            throw new IllegalArgumentException("preview path and source are required");
        return assets.preview(
                actor,
                workspaces.defaultWorkspace().id(),
                request.path(),
                request.body(),
                Optional.ofNullable(request.commit()));
    }

    @GetMapping("/assets/images/{token}")
    ResponseEntity<byte[]> image(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable String token) {
        var image = assets.readPrivateImage(actor, workspaces.defaultWorkspace().id(), token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .contentLength(image.size())
                .body(image.bytes());
    }

    record Preview(String path, String body, String commit) {}
}
