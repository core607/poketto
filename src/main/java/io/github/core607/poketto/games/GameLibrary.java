package io.github.core607.poketto.games;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Public delivery only returns previously validated, currently published packages; it starts no jobs. */
public interface GameLibrary {
    Optional<Published> find(String space, UUID articleId);

    Optional<Published> find(WorkspaceId workspace, UUID articleId);

    /** Holds the current public snapshot through the callback; callers must not read repository blobs inside it. */
    <T> T withCurrent(WorkspaceId workspace, UUID articleId, Function<Published, T> action);

    /** Display-only lookup beneath an already authorized snapshot guard; never acquires another guard or starts a job. */
    boolean recognizes(WorkspaceId workspace, String commit, UUID articleId);

    /** Recent bounded validation results for the current public snapshot; caller authorizes the workspace. */
    Inspection inspect(WorkspaceId workspace);

    record Inspection(String commit, List<Diagnostic> items) {
        public Inspection {
            items = List.copyOf(items);
        }
    }

    record Diagnostic(String articlePath, UUID articleId, String code, String detail) {}

    record Published(
            WorkspaceId workspaceId, UUID articleId, String title, String version, String help, GameBundle bundle) {}
}
