package io.github.core607.poketto.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves workspace-owned paths without accepting names, slugs, or caller-supplied fragments.
 */
public final class WorkspacePaths {

    private final Path workspacesDirectory;

    public WorkspacePaths(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "data directory must not be null");
        if (!dataDirectory.isAbsolute()) {
            throw new IllegalArgumentException("data directory must be absolute: " + dataDirectory);
        }
        this.workspacesDirectory = dataDirectory.toAbsolutePath().normalize().resolve("workspaces");
    }

    public Path contentDirectory(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        Path resolved = workspacesDirectory
                .resolve(workspaceId.toString())
                .resolve("content")
                .normalize();
        if (!resolved.startsWith(workspacesDirectory)) {
            throw new IllegalStateException(
                    "workspace content path escaped the configured data directory");
        }
        return resolved;
    }

    /** Returns the root containing server-derived workspace state. */
    public Path workspacesDirectory() {
        return workspacesDirectory;
    }
}
