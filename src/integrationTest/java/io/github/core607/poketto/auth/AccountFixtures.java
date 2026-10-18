package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;

/** Creates synthetic accounts without involving mail delivery in unrelated integration tests. */
public final class AccountFixtures {
    private AccountFixtures() {}

    public static AuthPrincipal create(AuthService auth, String login, String password) {
        return AuthService.accountPrincipal(auth.createAccount(login, auth.encodePassword(password), false));
    }

    /** Issues a key by its own holder, as an OAuth connection does; members cannot issue keys directly. */
    public static AuthPrincipal ownKey(
            AuthService auth, AuthPrincipal holder, WorkspaceId workspace, Set<Capability> capabilities) {
        return auth.authenticateApiKey(
                auth.createOAuthKey(holder, workspace, capabilities).token());
    }
}
