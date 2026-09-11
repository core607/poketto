package io.github.core607.poketto.auth;

/** Issuance eligibility and future allowance policies share this boundary; no per-user quota is implied. */
@FunctionalInterface
public interface RegistrationInvitationPolicy {
    boolean mayIssue(AccountIdentity issuer);

    static RegistrationInvitationPolicy configured(boolean ordinaryUsersEnabled) {
        return issuer -> issuer.siteAdministrator() || ordinaryUsersEnabled;
    }
}
