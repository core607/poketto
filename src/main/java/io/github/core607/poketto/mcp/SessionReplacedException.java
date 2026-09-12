package io.github.core607.poketto.mcp;

import java.util.Optional;

/** This request did not execute. An available ID belongs only to the caller's current transport and scope. */
public final class SessionReplacedException extends RuntimeException {
    public enum Reason {
        MISSING_COPY,
        DIFFERENT_COPY,
        CLOSED_COPY
    }

    private final Reason reason;
    private final Optional<String> currentCopyId;
    private final boolean newCopyAllowed;

    public SessionReplacedException(Reason reason, Optional<String> currentCopyId, boolean newCopyAllowed) {
        super("Expected working copy is unavailable or different; this command did not execute");
        if (reason == null || (reason == Reason.DIFFERENT_COPY) != currentCopyId.isPresent()) {
            throw new IllegalArgumentException("Only a different live copy has an available ID");
        }
        if ((reason == Reason.MISSING_COPY && !newCopyAllowed) || (reason == Reason.DIFFERENT_COPY && newCopyAllowed)) {
            throw new IllegalArgumentException("New admission requires no live copy");
        }
        currentCopyId.ifPresent(value -> {
            if (RepositoryExecutor.NEW_COPY.equals(RepositoryExecutor.requireCopyId(value))) {
                throw new IllegalArgumentException("Available copy requires an existing ID");
            }
        });
        this.reason = reason;
        this.currentCopyId = currentCopyId;
        this.newCopyAllowed = newCopyAllowed;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<String> currentCopyId() {
        return currentCopyId;
    }

    public boolean newCopyAllowed() {
        return newCopyAllowed;
    }
}
