package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
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
    private final boolean bearer;
    private final String challenge;

    WorkspaceIdentityFilter(ObjectProvider<AuthService> auth, boolean bearer, String issuer) {
        this.auth = auth;
        this.bearer = bearer;
        this.challenge = issuer.isBlank()
                ? "Bearer realm=\"poketto\""
                : "Bearer resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource\"";
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // Streamable HTTP completes through an async dispatch with no stored security context.
        return !bearer;
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
                    response.setHeader("WWW-Authenticate", challenge);
                    AuthHttpErrors.write(response, 401);
                    return;
                }
                AuthPrincipal principal =
                        auth.getObject().authenticateApiKey(headers.getFirst().substring(7));
                auth.getObject().workspaceForKey(principal);
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(
                                principal, null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
            } else {
                String path = AuthHttpErrors.path(request);
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (path.startsWith("/api/admin/")) {
                    if (authentication == null
                            || !authentication.isAuthenticated()
                            || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                            || principal.kind() != AuthPrincipal.Kind.ACCOUNT) {
                        AuthHttpErrors.write(response, 401);
                        return;
                    }
                    if (!path.startsWith(WorkspaceHttpRoutes.ADMIN)) {
                        AuthHttpErrors.write(response, 404);
                        return;
                    }
                    auth.getObject().authorize(principal, WorkspaceHttpRoutes.workspace(path));
                }
            }
        } catch (IllegalArgumentException invalid) {
            AuthHttpErrors.write(response, 400);
            return;
        } catch (AuthException exception) {
            SecurityContextHolder.clearContext();
            if (bearer) {
                response.setHeader("WWW-Authenticate", challenge);
            }
            AuthHttpErrors.write(response, bearer ? 401 : 403);
            return;
        }
        chain.doFilter(request, response);
    }
}
