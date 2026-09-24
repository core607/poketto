package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.capture.CaptureInbox;
import io.github.core607.poketto.capture.CaptureLimitException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Two entrances to the capture inbox: {@code /api/capture} for a key held by a phone shortcut, whose
 * workspace is the key's own, and a session entrance for the same-origin bookmarklet popup.
 */
@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class CaptureController {
    private final CaptureInbox inbox;
    private final AuthService auth;

    CaptureController(CaptureInbox inbox, AuthService auth) {
        this.inbox = inbox;
        this.auth = auth;
    }

    @PostMapping(path = "/api/capture", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CaptureInbox.Captured> capture(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestBody CaptureRequest request) {
        return created(inbox.capture(actor, keyWorkspace(actor), request.capture(), Optional.empty()));
    }

    @PostMapping(path = "/api/capture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<CaptureInbox.Captured> captureForm(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String url,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String note,
            @RequestPart(required = false) MultipartFile image)
            throws IOException {
        WorkspaceId workspace = keyWorkspace(actor);
        var capture = new CaptureInbox.Capture(title, url, text, note);
        if (image == null || image.isEmpty()) {
            return created(inbox.capture(actor, workspace, capture, Optional.empty()));
        }
        try (InputStream bytes = image.getInputStream()) {
            return created(inbox.capture(actor, workspace, capture, Optional.of(bytes)));
        }
    }

    @PostMapping(path = "/api/admin/workspaces/{workspaceId}/capture", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CaptureInbox.Captured> captureInBrowser(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestBody CaptureRequest request) {
        return created(inbox.capture(actor, WorkspaceId.parse(workspaceId), request.capture(), Optional.empty()));
    }

    // OAuth connection tokens are issued for the MCP entrance alone.
    private WorkspaceId keyWorkspace(AuthPrincipal actor) {
        if (auth.connectionBacked(actor)) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        return auth.workspaceForKey(actor);
    }

    private static ResponseEntity<CaptureInbox.Captured> created(CaptureInbox.Captured captured) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(captured);
    }

    @ExceptionHandler(CaptureLimitException.class)
    ProblemDetail limited() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Capture limit reached; try again later");
    }

    record CaptureRequest(String title, String url, String text, String note) {
        CaptureInbox.Capture capture() {
            return new CaptureInbox.Capture(title, url, text, note);
        }
    }
}
