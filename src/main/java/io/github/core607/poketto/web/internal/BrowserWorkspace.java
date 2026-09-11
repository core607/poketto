package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Servlet request injection resolves the current request, never a session-wide selection. */
@Component
final class BrowserWorkspace {
    private final HttpServletRequest request;

    BrowserWorkspace(HttpServletRequest request) {
        this.request = request;
    }

    WorkspaceId selected() {
        return WorkspaceHttpRoutes.workspace(
                request.getRequestURI().substring(request.getContextPath().length()));
    }
}
