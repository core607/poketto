package io.github.core607.poketto.mcp;

import java.util.Objects;
import java.util.Optional;

/** The requested command has not started; this does not classify any previous command's outcome. */
public final class ExecutionAdmissionException extends RuntimeException {
    public enum Reason {
        RECOVERY_REQUIRED,
        MISSING_COPY,
        EXPIRED,
        BUSY,
        CAPACITY,
        UNAVAILABLE,
        /** Publication changed under a public copy that has local work; the copy is kept untouched. */
        PUBLICATION_CHANGED
    }

    private final Reason reason;
    private final boolean recoveryAvailable;
    private final Optional<String> copyId;

    public ExecutionAdmissionException(Reason reason, boolean recoveryAvailable) {
        this(reason, recoveryAvailable, null);
    }

    public ExecutionAdmissionException(Reason reason, boolean recoveryAvailable, Throwable cause) {
        this(reason, recoveryAvailable, Optional.empty(), cause);
    }

    private ExecutionAdmissionException(
            Reason reason, boolean recoveryAvailable, Optional<String> copyId, Throwable cause) {
        super(
                "Execution admission refused: " + Objects.requireNonNull(reason, "admission reason must be present"),
                cause);
        this.reason = reason;
        this.recoveryAvailable = recoveryAvailable;
        this.copyId = copyId;
    }

    /**
     * The kept copy is named because a caller that sent {@code new} needs its ID to discard it; the
     * ID is not a credential, since discarding still requires the account's current authorization.
     */
    public static ExecutionAdmissionException publicationChanged(String copyId) {
        if (RepositoryExecutor.NEW_COPY.equals(RepositoryExecutor.requireCopyId(copyId))) {
            throw new IllegalArgumentException("A changed publication names the existing copy");
        }
        return new ExecutionAdmissionException(Reason.PUBLICATION_CHANGED, true, Optional.of(copyId), null);
    }

    public Reason reason() {
        return reason;
    }

    public boolean recoveryAvailable() {
        return recoveryAvailable;
    }

    /** The copy the caller must act on; present only for {@link Reason#PUBLICATION_CHANGED}. */
    public Optional<String> copyId() {
        return copyId;
    }
}
