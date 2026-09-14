package io.github.core607.poketto.mcp;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;

/** A transport has closed; account working copies have an independent lifetime. */
public record McpSessionClosed(WorkspaceId workspaceId, UUID keyId, String sessionId, Reason reason) {
    public enum Reason {
        CLIENT_DELETE,
        IDLE_EXPIRY,
        AUTH_REVOKED,
        INITIALIZATION_FAILED,
        SHUTDOWN
    }
}
