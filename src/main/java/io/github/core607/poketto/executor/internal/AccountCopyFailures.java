package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryEmptyException;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.SessionReplacedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admission failures describe the account journal independently of its sandbox transport. */
final class AccountCopyFailures {
    private static final Logger log = LoggerFactory.getLogger(AccountCopyFailures.class);

    private AccountCopyFailures() {}

    static RuntimeException admissionFromJournal(RuntimeException failure, AccountCommand held) {
        AccountCopyRecord record = null;
        try {
            record = held.record();
        } catch (RetainedCopyException unreadable) {
            failure.addSuppressed(unreadable);
            log.warn("Failed admission journal cannot be inspected", unreadable);
        }
        return admission(failure, record);
    }

    static RuntimeException admission(RuntimeException failure, AccountCopyRecord record) {
        if (failure instanceof ExecutionAdmissionException refused) {
            return record == null ? refused : refused.withCopyId(record.copyId().toString());
        }
        if (failure instanceof ExecutionUnconfirmedException
                || failure instanceof AuthException
                || failure instanceof SecurityException
                || failure instanceof IllegalArgumentException
                || failure instanceof SessionReplacedException
                || failure instanceof ContentRepositoryException
                || failure instanceof RepositoryEmptyException) {
            return failure;
        }
        log.warn("Account copy admission failed", failure);
        ExecutionAdmissionException.Reason reason = failure instanceof RetainedCopyException retained
                ? switch (retained.reason()) {
                    case MISSING -> ExecutionAdmissionException.Reason.MISSING_COPY;
                    case EXPIRED -> ExecutionAdmissionException.Reason.EXPIRED;
                    case BUSY -> ExecutionAdmissionException.Reason.BUSY;
                    case LIMIT -> ExecutionAdmissionException.Reason.CAPACITY;
                    case STALE, UNCERTAIN -> ExecutionAdmissionException.Reason.RECOVERY_REQUIRED;
                    case UNAVAILABLE -> ExecutionAdmissionException.Reason.UNAVAILABLE;
                }
                : ExecutionAdmissionException.Reason.UNAVAILABLE;
        boolean available = record != null && record.expiresAt() > System.currentTimeMillis();
        var refused = new ExecutionAdmissionException(reason, available, failure);
        return record == null ? refused : refused.withCopyId(record.copyId().toString());
    }
}
