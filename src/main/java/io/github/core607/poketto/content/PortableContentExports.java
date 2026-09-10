package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Temporary authorized packages. A handle never grants access independently of its owner and scope. */
public interface PortableContentExports {
    /** Client identity, when supplied, is the server-issued MCP session hash, never a sandbox argument. */
    Export create(
            AuthPrincipal actor,
            WorkspaceId workspace,
            List<String> selections,
            boolean publicOnly,
            Optional<String> client);

    Export describe(AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client);

    /** Rechecks authorization during transfer. Leaves caller output open; failures can leave partial output. */
    void copyTo(AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client, OutputStream output);

    void release(AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client);

    /** Trusted session-lifecycle callback; permits cleanup after revocation without returning any content. */
    void closeClient(AuthPrincipal actor, WorkspaceId workspace, String client);

    record Export(UUID handle, boolean publicOnly, long bytes, String sha256, Instant expiresAt) {}
}
