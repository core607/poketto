package io.github.core607.poketto.content;

/** Fixed ingress errors exclude signatures, secrets and webhook payloads. */
public final class GitHubWebhookException extends RuntimeException {
    public enum Code {
        INVALID_SIGNATURE,
        MALFORMED,
        REPLAYED,
        UNAVAILABLE,
        BUSY,
        TOO_LARGE
    }

    private final Code code;

    public GitHubWebhookException(Code code) {
        super("GitHub webhook: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
