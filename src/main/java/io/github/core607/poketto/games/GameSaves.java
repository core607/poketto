package io.github.core607.poketto.games;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Browser saves are untrusted account data; machine entrances additionally require current holder consent. */
public interface GameSaves {
    Index index(AuthPrincipal actor, WorkspaceId connection);

    View play(AuthPrincipal actor, WorkspaceId connection, String reference, String creationRequest);

    View peek(AuthPrincipal actor, WorkspaceId connection, UUID save);

    View press(AuthPrincipal actor, WorkspaceId connection, UUID save, String action, String expectedRevision);

    View store(AuthPrincipal actor, Upload input);

    /** Browser resume reads state only and never runs a server game step. */
    View load(AuthPrincipal actor, UUID save);

    boolean remove(AuthPrincipal actor, WorkspaceId connection, UUID save);

    record Index(UUID accountId, List<Summary> items, String nextCreationRequest) {
        public Index {
            items = List.copyOf(items);
        }
    }

    record Summary(UUID saveId, String revision, UUID workspaceId, UUID articleId, String title, String status) {}

    record View(
            UUID saveId,
            String revision,
            String packageVersion,
            String title,
            UUID workspaceId,
            UUID articleId,
            GameRunner.Result result) {}

    record Upload(
            UUID accountId,
            UUID saveId,
            String creationRequest,
            String expectedRevision,
            String space,
            UUID articleId,
            String packageVersion,
            JsonNode state) {
        public Upload {
            if (accountId == null || articleId == null || state == null) {
                throw new IllegalArgumentException("A game upload requires its account, article ID and state");
            }
            GameBundle.text(space, 128, "Space slug");
            if (packageVersion == null || !packageVersion.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("A game upload requires its package version");
            }
            if (saveId == null) {
                counter(creationRequest, false);
            } else {
                counter(expectedRevision, true);
            }
        }
    }

    static long counter(String value, boolean zero) {
        if (value == null || !value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("A game request or revision must be a decimal counter");
        }
        return Long.parseLong(value);
    }
}
