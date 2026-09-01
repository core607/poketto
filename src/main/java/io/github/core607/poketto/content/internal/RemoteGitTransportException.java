package io.github.core607.poketto.content.internal;

/** Internal marker whose message is safe to surface because it contains no transport details. */
final class RemoteGitTransportException extends RuntimeException {

    RemoteGitTransportException(String operation) {
        super("remote repository " + operation + " failed");
    }
}
