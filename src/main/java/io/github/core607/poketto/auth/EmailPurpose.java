package io.github.core607.poketto.auth;

/** Each operation consumes only challenges issued for that operation. */
public enum EmailPurpose {
    SIGNUP,
    BIND,
    RECOVERY
}
