package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.PortableContentExports;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/workspaces/{workspaceId}/exports")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class ContentExportController {
    private final PortableContentExports exports;
    private final BrowserWorkspace workspaces;

    ContentExportController(PortableContentExports exports, BrowserWorkspace workspaces) {
        this.exports = exports;
        this.workspaces = workspaces;
    }

    record Request(List<String> paths, Boolean publicOnly) {}

    @PostMapping
    PortableContentExports.Export create(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody Request request) {
        if (request.paths() == null || request.publicOnly() == null) {
            throw new IllegalArgumentException("export paths and scope are required");
        }
        return exports.create(actor, workspaces.selected(), request.paths(), request.publicOnly(), Optional.empty());
    }

    @GetMapping("/{handle}/metadata")
    PortableContentExports.Export describe(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID handle) {
        return exports.describe(actor, workspaces.selected(), handle, Optional.empty());
    }

    @GetMapping("/{handle}")
    void download(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID handle, HttpServletResponse response) {
        var workspace = workspaces.selected();
        var receipt = exports.describe(actor, workspace, handle, Optional.empty());
        exports.copyTo(actor, workspace, handle, Optional.empty(), new DeferredDownload(response, () -> {
            response.setHeader(
                    "Content-Disposition",
                    "attachment; filename=\"poketto-" + (receipt.publicOnly() ? "public" : "private") + ".zip\"");
            response.setContentType("application/zip");
            response.setContentLengthLong(receipt.bytes());
        }));
    }

    @PostMapping("/{handle}/release")
    ResponseEntity<Void> release(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID handle) {
        exports.release(actor, workspaces.selected(), handle, Optional.empty());
        return ResponseEntity.noContent().build();
    }
}
