package io.github.core607.poketto.mcp;

import java.util.Objects;

/** The requested command has not started; this does not classify any previous command's outcome. */
public final class ExecutionAdmissionException extends RuntimeException {
    public enum Reason {
        RECOVERY_REQUIRED,
        MISSING_COPY,
        EXPIRED,
        BUSY,
        CAPACITY,
        UNAVAILABLE
    }

    private final Reason reason;
    private final boolean recoveryAvailable;

    public ExecutionAdmissionException(Reason reason, boolean recoveryAvailable) {
        this(reason, recoveryAvailable, null);
    }

    public ExecutionAdmissionException(Reason reason, boolean recoveryAvailable, Throwable cause) {
        super(
                "Execution admission refused: " + Objects.requireNonNull(reason, "admission reason must be present"),
                cause);
        this.reason = reason;
        this.recoveryAvailable = recoveryAvailable;
    }

    public Reason reason() {
        return reason;
    }

    public boolean recoveryAvailable() {
        return recoveryAvailable;
    }
}
