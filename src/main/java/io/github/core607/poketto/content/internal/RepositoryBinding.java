package io.github.core607.poketto.content.internal;

import java.util.Objects;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/** Adapter-owned remote coordinates. Never pass this value across the authority port. */
final class RepositoryBinding {

    private final URIish location;
    private final CredentialsProvider credentials;

    RepositoryBinding(URIish location, CredentialsProvider credentials) {
        this.location = Objects.requireNonNull(location, "remote location must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
    }

    URIish location() {
        return location;
    }

    CredentialsProvider credentials() {
        return credentials;
    }

    @Override
    public String toString() {
        return "RepositoryBinding[redacted]";
    }
}
