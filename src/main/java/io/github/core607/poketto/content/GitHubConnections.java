package io.github.core607.poketto.content;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.core607.poketto.auth.AuthPrincipal;
import java.time.Instant;
import java.util.UUID;

/** Personal GitHub repository authorization, separate from Poketto login and space membership. */
public interface GitHubConnections {
    Status status(AuthPrincipal actor);

    /** The caller keeps this authorization in the initiating browser session and consumes it once. */
    Authorization begin(AuthPrincipal actor);

    /** Exchanges credentials outside transactions; requireSession rechecks the initiating browser before saving. */
    void complete(AuthPrincipal actor, Authorization authorization, String code, Runnable requireSession);

    /** Invalidates the specified grant version without deleting repositories or space membership. */
    Status disconnect(AuthPrincipal actor, long expectedVersion);

    /** Returns the verified personal account's App installation or installation-settings entrance. */
    String installationUrl(AuthPrincipal actor);

    enum State {
        DISABLED,
        NOT_CONNECTED,
        CONNECTED,
        REFRESHING,
        REAUTHORIZATION,
        DISCONNECTED
    }

    record Status(
            boolean available, boolean eligibleToCreate, State state, Long githubUserId, String login, long version) {}

    record Authorization(
            String url,
            String state,
            @JsonIgnore String verifier,
            @JsonIgnore Instant issuedAt,
            @JsonIgnore UUID accountId,
            @JsonIgnore long credentialVersion,
            @JsonIgnore long grantVersion) {
        @Override
        public String toString() {
            return "GitHubAuthorization[redacted]";
        }
    }
}
