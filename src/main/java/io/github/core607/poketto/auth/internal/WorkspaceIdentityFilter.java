package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class WorkspaceIdentityFilter extends OncePerRequestFilter {
    private final ObjectProvider<AuthService> auth;
    private final ObjectProvider<WorkspaceCatalog> workspaces;
    private final boolean bearer;

    WorkspaceIdentityFilter(
            ObjectProvider<AuthService> auth, ObjectProvider<WorkspaceCatalog> workspaces, boolean bearer) {
        this.auth = auth;
        this.workspaces = workspaces;
        this.bearer = bearer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (bearer) {
                var headers = Collections.list(request.getHeaders("Authorization"));
                if (headers.size() != 1
                        || !headers.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)
                        || headers.getFirst().length() > 263
                        || auth.getIfAvailable() == null) {
                    response.setHeader("WWW-Authenticate", "Bearer realm=\"poketto\"");
                    AuthHttpErrors.write(response, 401);
                    return;
                }
                AuthPrincipal principal =
                        auth.getObject().authenticateApiKey(headers.getFirst().substring(7));
                auth.getObject()
                        .authorize(
                                principal,
                                workspaces.getObject().defaultWorkspace().id());
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
            } else {
                String path = AuthHttpErrors.path(request);
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if ((path.startsWith("/api/admin/") || path.startsWith("/api/private/") || path.equals("/api/auth/me"))
                        && authentication != null
                        && authentication.getPrincipal() instanceof AuthPrincipal principal) {
                    if (principal.kind() != AuthPrincipal.Kind.ACCOUNT) {
                        AuthHttpErrors.write(response, 401);
                        return;
                    }
                    auth.getObject()
                            .authorize(
                                    principal,
                                    workspaces.getObject().defaultWorkspace().id());
                }
            }
        } catch (AuthException exception) {
            SecurityContextHolder.clearContext();
            if (bearer) response.setHeader("WWW-Authenticate", "Bearer realm=\"poketto\"");
            AuthHttpErrors.write(response, bearer ? 401 : 403);
            return;
        }
        chain.doFilter(request, response);
    }
}
