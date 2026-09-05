package io.github.core607.poketto.mcp;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;

/** Execution owners release this trusted session's lease and descendants when it closes. */
public record McpSessionClosed(WorkspaceId workspaceId, UUID keyId, String sessionId, Reason reason) {
    public enum Reason {
        CLIENT_DELETE,
        IDLE_EXPIRY,
        AUTH_REVOKED,
        INITIALIZATION_FAILED,
        SHUTDOWN
    }
}
