package io.github.core607.poketto.auth;

/**
 * What an authorized principal may do inside one workspace.
 *
 * <p>Where the check sits differs by service and is stated by each. The authorized reader checks
 * on every call it serves; the document writer states that it does not authorize at all and that
 * its entry point must have resolved the capability first. A reviewer tracing a new caller has to
 * follow it to whichever of the two it reaches, because reading one of them does not answer for
 * the other.
 */
public enum Capability {
    READ_PRIVATE,
    WRITE_PRIVATE,
    PUBLISH,
    MANAGE_KEYS,
    EXECUTE_REPOSITORY
}
