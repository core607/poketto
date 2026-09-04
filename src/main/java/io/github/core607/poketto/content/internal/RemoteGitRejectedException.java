package io.github.core607.poketto.content.internal;

/**
 * The remote answered and refused the ref update: {@code main} did not advance. Unlike its
 * parent, this outcome is definite, so no reconciliation read is needed.
 */
final class RemoteGitRejectedException extends RemoteGitTransportException {

    RemoteGitRejectedException(String operation) {
        super(operation, "was rejected by the remote");
    }
}
