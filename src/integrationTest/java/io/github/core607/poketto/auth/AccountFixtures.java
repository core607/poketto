package io.github.core607.poketto.auth;

/** Creates synthetic accounts without involving mail delivery in unrelated integration tests. */
public final class AccountFixtures {
    private AccountFixtures() {}

    public static AuthPrincipal create(AuthService auth, String login, String password) {
        return AuthService.accountPrincipal(auth.createAccount(login, auth.encodePassword(password), false));
    }
}
