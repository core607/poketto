package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One admitted command and the exact pool claim that its cancellation may contain. */
final class NativeRepositoryOperation {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryOperation.class);
    final UUID id = UUID.randomUUID();
    final AuthPrincipal principal;
    final AccountCopyRecord.Owner owner;
    final AccountOperations.Slot account;
    volatile NativeRepositoryLease lease;
    volatile NativeRepositoryPool.Claim claim;
    volatile boolean managed;
    volatile AccountCopyRecord authorizedCopy;
    volatile long authorityCheck;
    volatile boolean attempted;
    volatile boolean finished;
    volatile boolean operationComplete;
    volatile boolean reusableRefusal;
    volatile boolean cancelled;
    volatile UUID execution;

    NativeRepositoryOperation(AuthPrincipal principal, AccountCopyRecord.Owner owner, AccountOperations.Slot account) {
        this.principal = principal;
        this.owner = owner;
        this.account = account;
    }

    void checkGrant(NativeRepositoryWorkspace workspaces, long now) {
        var record = authorizedCopy;
        if (!managed || finished || cancelled || record == null || now - authorityCheck < 0) {
            return;
        }
        authorityCheck = now + java.time.Duration.ofSeconds(30).toNanos();
        try {
            workspaces.authorize(principal, owner, record);
        } catch (RuntimeException inaccessible) {
            cancel();
        }
    }

    void cancel() {
        cancelled = true;
        try {
            if (managed && lease != null && !operationComplete) {
                operationComplete = lease.cancelWorkspace();
            } else if (claim != null) {
                claim.invalidate();
            }
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            log.warn("Native repository containment remains unconfirmed", failure);
        }
    }
}
