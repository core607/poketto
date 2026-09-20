package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.ModerationContent;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/site/workspaces/{workspaceId}/review")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class ModerationContentController {
    private final ModerationContent content;

    ModerationContentController(ModerationContent content) {
        this.content = content;
    }

    @GetMapping
    ResponseEntity<AuthService.Page<ModerationContent.Article>> articles(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(content.articles(actor, WorkspaceId.parse(workspaceId), offset, limit));
    }

    @GetMapping("/document")
    ResponseEntity<ModerationContent.Document> document(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestParam String route) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(content.document(actor, WorkspaceId.parse(workspaceId), route));
    }

    @GetMapping("/images/{token}")
    ResponseEntity<byte[]> image(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @PathVariable String token) {
        var image = content.image(actor, WorkspaceId.parse(workspaceId), token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .contentLength(image.size())
                .body(image.bytes());
    }

    @GetMapping("/download")
    void download(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestParam String commit,
            @RequestParam String route,
            @RequestParam String path,
            HttpServletResponse response) {
        MediaFileController.send(
                content.download(actor, WorkspaceId.parse(workspaceId), commit, route, path), response);
    }
}
