package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class MediaFileController {
    private final MediaFileService media;
    private final WorkspaceCatalog workspaces;

    MediaFileController(MediaFileService media, WorkspaceCatalog workspaces) {
        this.media = media;
        this.workspaces = workspaces;
    }

    @PostMapping(
            path = "/api/admin/workspaces/{workspaceId}/media",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ManagedAsset upload(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Media-Type", defaultValue = "application/octet-stream") String type,
            HttpServletRequest request)
            throws IOException {
        try (var input = request.getInputStream()) {
            return media.upload(actor, WorkspaceId.parse(workspaceId), key, type, input);
        }
    }

    @GetMapping("/api/admin/workspaces/{workspaceId}/media")
    void privateDownload(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestParam String path,
            @RequestParam(required = false) String commit,
            HttpServletResponse response) {
        send(media.privateDownload(actor, WorkspaceId.parse(workspaceId), Optional.ofNullable(commit), path), response);
    }

    @GetMapping("/api/public/media")
    void publicDownload(
            @RequestParam String path,
            @RequestParam String commit,
            @RequestParam String route,
            HttpServletResponse response) {
        send(media.publicDownload(workspaces.defaultWorkspace().id(), commit, route, path), response);
    }

    private static void send(MediaFileService.Download download, HttpServletResponse response) {
        download.writeTo(new DeferredDownload(response, () -> {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(
                    "Content-Disposition",
                    ContentDisposition.attachment()
                            .filename(download.filename(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            response.setContentLengthLong(download.size());
        }));
    }
}
