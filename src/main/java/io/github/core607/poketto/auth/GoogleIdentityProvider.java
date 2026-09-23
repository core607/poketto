package io.github.core607.poketto.auth;

/** Google basic identity only; provider tokens never become application credentials. */
public interface GoogleIdentityProvider {
    boolean available();

    Authorization begin();

    GoogleAccounts.Identity exchange(Authorization authorization, String code);

    record Authorization(String url, String state, String nonce, String verifier) implements java.io.Serializable {
        @Override
        public String toString() {
            return "GoogleAuthorization[REDACTED]";
        }
    }
}
