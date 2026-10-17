package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the API key behind each MCP request. The transport is stateless, so every request carries
 * its own authentication and every tool call reauthorizes its workspace; nothing outlives a request.
 */
final class McpIdentities {
    static final String IDENTITY_CONTEXT = "poketto.mcp.identity";
    static final String CLIENT_CONTEXT = "poketto.mcp.client";
    private final AuthService auth;

    McpIdentities(AuthService auth) {
        this.auth = auth;
    }

    Identity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || principal.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new SecurityException("MCP authentication required");
        }
        WorkspaceId workspace = auth.workspaceForKey(principal);
        return new Identity(principal, workspace);
    }

    Identity resolve(McpTransportContext context) {
        if (!(context.get(IDENTITY_CONTEXT) instanceof Identity identity)) {
            throw new SecurityException("MCP request identity unavailable");
        }
        auth.authorize(identity.principal(), identity.workspace());
        return identity;
    }

    /** The client's self-reported name from its User-Agent product token, or empty. */
    static String client(McpTransportContext context) {
        return context.get(CLIENT_CONTEXT) instanceof String client ? client : "";
    }

    record Identity(AuthPrincipal principal, WorkspaceId workspace) {}
}
