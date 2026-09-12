package io.github.core607.poketto.auth;

/**
 * What an authorized principal may do inside one workspace. A capability is checked at the
 * operation that performs the work, never only at the entrance that routed to it, so an
 * alternate caller cannot reach an operation by skipping a wrapper.
 */
public enum Capability {
    READ_PRIVATE,
    WRITE_PRIVATE,
    PUBLISH,
    MANAGE_KEYS,
    EXECUTE_REPOSITORY
}
