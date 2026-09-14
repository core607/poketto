package io.github.core607.poketto.mcp;

import java.util.Objects;

/** A retained command was attempted, but its completion was not acknowledged to the caller. */
public final class ExecutionUnconfirmedException extends RuntimeException {
    private final String copyId;
    private final RepositoryExecutor.CopyRetention retention;
    private final boolean recoveryAvailable;

    public ExecutionUnconfirmedException(
            String copyId, RepositoryExecutor.CopyRetention retention, boolean recoveryAvailable, Throwable cause) {
        super("Retained command completion was not confirmed", cause);
        this.copyId = RepositoryExecutor.requireCopyId(copyId);
        if (RepositoryExecutor.NEW_COPY.equals(copyId)) {
            throw new IllegalArgumentException("An unconfirmed command requires its actual copy ID");
        }
        this.retention = Objects.requireNonNull(retention, "Retained command identity must be present");
        this.recoveryAvailable = recoveryAvailable;
    }

    public String copyId() {
        return copyId;
    }

    public RepositoryExecutor.CopyRetention retention() {
        return retention;
    }

    public boolean recoveryAvailable() {
        return recoveryAvailable;
    }
}
