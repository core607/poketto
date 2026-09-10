package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/exports")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class ContentExportController {
    private final PortableContentExports exports;
    private final WorkspaceCatalog workspaces;

    ContentExportController(PortableContentExports exports, WorkspaceCatalog workspaces) {
        this.exports = exports;
        this.workspaces = workspaces;
    }

    record Request(List<String> paths, Boolean publicOnly) {}

    @PostMapping
    PortableContentExports.Export create(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody Request request) {
        if (request.paths() == null || request.publicOnly() == null)
            throw new IllegalArgumentException("export paths and scope are required");
        return exports.create(
                actor, workspaces.defaultWorkspace().id(), request.paths(), request.publicOnly(), Optional.empty());
    }

    @GetMapping("/{handle}/metadata")
    PortableContentExports.Export describe(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID handle) {
        return exports.describe(actor, workspaces.defaultWorkspace().id(), handle, Optional.empty());
    }

    @GetMapping("/{handle}")
    void download(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID handle, HttpServletResponse response) {
        var workspace = workspaces.defaultWorkspace().id();
        var receipt = exports.describe(actor, workspace, handle, Optional.empty());
        exports.copyTo(actor, workspace, handle, Optional.empty(), new OutputStream() {
            private OutputStream stream;

            private OutputStream stream() throws IOException {
                if (stream == null) {
                    // No success headers are committed before integrity and download authorization pass.
                    response.setHeader("Cache-Control", "no-store");
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    response.setHeader(
                            "Content-Disposition",
                            "attachment; filename=\"poketto-" + (receipt.publicOnly() ? "public" : "private")
                                    + ".zip\"");
                    response.setContentType("application/zip");
                    response.setContentLengthLong(receipt.bytes());
                    stream = response.getOutputStream();
                }
                return stream;
            }

            @Override
            public void write(int value) throws IOException {
                stream().write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int count) throws IOException {
                stream().write(bytes, offset, count);
            }
        });
    }

    @PostMapping("/{handle}/release")
    ResponseEntity<Void> release(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID handle) {
        exports.release(actor, workspaces.defaultWorkspace().id(), handle, Optional.empty());
        return ResponseEntity.noContent().build();
    }
}
