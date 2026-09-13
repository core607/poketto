package io.github.core607.poketto.spaces;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.Set;
import java.util.function.Supplier;

/** Human owners control website delivery; content-write grants do not grant website administration. */
public final class SpacePublicationService {
    private final AuthService auth;
    private final WorkspacePublications publications;

    public SpacePublicationService(AuthService auth, WorkspacePublications publications) {
        this.auth = auth;
        this.publications = publications;
    }

    public WorkspacePublications.Publication settings(AuthPrincipal actor, WorkspaceId workspace) {
        return asOwner(actor, workspace, () -> publications.settings(workspace));
    }

    public WorkspacePublications.Publication setEnabled(AuthPrincipal actor, WorkspaceId workspace, boolean enabled) {
        return asOwner(actor, workspace, () -> publications.setEnabled(workspace, enabled));
    }

    private <T> T asOwner(AuthPrincipal actor, WorkspaceId workspace, Supplier<T> action) {
        if (actor == null || actor.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        return auth.withAuthorization(actor, workspace, Set.of(), () -> {
            if (auth.authorize(actor, workspace).role() != MembershipRole.OWNER) {
                throw new AuthException(AuthException.Code.DENIED);
            }
            return action.get();
        });
    }
}
