package io.github.core607.poketto.mcp;

import java.util.Objects;

/** The requested command has not started; this does not classify any previous command's outcome. */
public final class ExecutionAdmissionException extends RuntimeException {
    public enum Reason {
        RECOVERY_REQUIRED,
        GENERATION_MISMATCH,
        MISSING_COPY,
        EXPIRED,
        BUSY,
        CAPACITY,
        UNAVAILABLE
    }

    private final Reason reason;
    private final Long currentGeneration;
    private final boolean recoveryAvailable;

    public ExecutionAdmissionException(Reason reason, Long currentGeneration, boolean recoveryAvailable) {
        this(reason, currentGeneration, recoveryAvailable, null);
    }

    public ExecutionAdmissionException(
            Reason reason, Long currentGeneration, boolean recoveryAvailable, Throwable cause) {
        super(
                "Execution admission refused: " + Objects.requireNonNull(reason, "admission reason must be present"),
                cause);
        if (currentGeneration != null
                && (currentGeneration < 1 || currentGeneration > RepositoryExecutor.MAX_GENERATION)) {
            throw new IllegalArgumentException("Current generation must be a positive safe integer");
        }
        this.reason = reason;
        this.currentGeneration = currentGeneration;
        this.recoveryAvailable = recoveryAvailable;
    }

    public Reason reason() {
        return reason;
    }

    public Long currentGeneration() {
        return currentGeneration;
    }

    public boolean recoveryAvailable() {
        return recoveryAvailable;
    }
}
