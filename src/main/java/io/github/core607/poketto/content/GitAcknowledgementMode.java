package io.github.core607.poketto.content;

/** Defines when a document write may be acknowledged to its caller. */
public enum GitAcknowledgementMode {
    /** A local {@code main} commit is sufficient; replication continues asynchronously. */
    LOCAL,

    /** The configured remote must accept the commit before local {@code main} advances. */
    MIRRORED
}
