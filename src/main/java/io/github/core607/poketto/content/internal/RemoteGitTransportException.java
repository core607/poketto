package io.github.core607.poketto.content.internal;

/**
 * The remote operation ended without a usable answer, so its outcome is unknown. The message is
 * safe to surface because it contains no transport details.
 */
class RemoteGitTransportException extends RuntimeException {

    RemoteGitTransportException(String operation) {
        this(operation, "failed");
    }

    RemoteGitTransportException(String operation, String outcome) {
        super("remote repository " + operation + " " + outcome);
    }
}
