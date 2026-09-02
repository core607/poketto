package io.github.core607.poketto.web.internal;

/**
 * Reports that a public entrance holds no resource for the requested identifier. Malformed,
 * unknown, and private identifiers all raise it so that a response cannot reveal which applied.
 */
final class PublicResourceNotFoundException extends RuntimeException {

    PublicResourceNotFoundException(String message) {
        super(message);
    }
}
