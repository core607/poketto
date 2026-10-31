package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardRequest;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardResult;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardStatus;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owner-authorized disposal and expiry share the journal lock and confirmed native volume deletion. */
final class NativeRepositoryDisposal implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryDisposal.class);
    private final SandcrabClient client;
    private final AuthService auth;
    private final AccountCopyStore accounts;
    private final NativeRepositoryPool pool;
    private final NativeRepositoryWorkspace workspaces;
    private final Consumer<UUID> removed;
    private final AtomicBoolean closed = new AtomicBoolean();

    NativeRepositoryDisposal(
            SandcrabClient client,
            AuthService auth,
            AccountCopyStore accounts,
            NativeRepositoryPool pool,
            NativeRepositoryWorkspace workspaces,
            Consumer<UUID> removed) {
        this.client = client;
        this.auth = auth;
        this.accounts = accounts;
        this.pool = pool;
        this.workspaces = workspaces;
        this.removed = removed;
    }

    DiscardResult discard(
            AuthPrincipal principal,
            WorkspaceId workspace,
            DiscardRequest request,
            ExecutionCancellation cancellation) {
        Runnable authorize = () -> authorize(principal, workspace, cancellation);
        authorize.run();
        var permitted = new AccountCopyRecord.Owner(principal.accountId(), workspace.value(), true);
        AccountCopyRecord.Owner owner = accounts.ownerFor(permitted, request.id());
        try (var held = new AccountCommand(accounts, owner, cancellation)) {
            AccountCopyRecord record = held.record();
            if (record == null || !record.copyId().toString().equals(request.id())) {
                return new DiscardResult(request.id(), DiscardStatus.ABSENT);
            }
            if (record.writer().storage() == null) {
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, true);
            }
            dispose(held, authorize);
            return new DiscardResult(request.id(), DiscardStatus.DISCARDED);
        } catch (RuntimeException failure) {
            throw IsolatedRepositoryExecutor.admissionFailure(failure, null);
        }
    }

    int collectExpiredCopies() {
        int count = 0;
        for (AccountCopyRecord.Owner owner : accounts.collectionCandidates(8)) {
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                Optional<AccountCommand> idle = AccountCommand.ifIdle(accounts, owner);
                if (idle.isEmpty()) {
                    continue;
                }
                try (AccountCommand held = idle.get()) {
                    AccountCopyRecord record = held.record();
                    if (record == null || record.writer().storage() == null) {
                        continue;
                    }
                    if (record.phase() != AccountCopyRecord.Phase.DISCARDING && !accounts.expired(record)) {
                        continue;
                    }
                    dispose(held, this::requireOpen);
                    count++;
                }
            } catch (RuntimeException failure) {
                log.warn("Native copy collection awaits confirmed cleanup", failure);
            }
        }
        return count;
    }

    private void dispose(AccountCommand held, Runnable authorize) {
        AccountCopyRecord record = held.record();
        authorize.run();
        if (record.phase() != AccountCopyRecord.Phase.DISCARDING) {
            workspaces.fence(record, client.info());
            // Persist the fence before pool cleanup can forget the session receipt.
            held.beginDiscard();
        }
        authorize.run();
        pool.closeCopy(record.copyId());
        SandcrabVolume volume =
                client.volume(record.writer().storage().volumeId().toString());
        record.writer().storage().requireVolume(volume);
        SandcrabVolume deleted = delete(volume, authorize);
        authorize.run();
        held.remove();
        releaseReceipts(record.copyId(), deleted);
    }

    private SandcrabVolume delete(SandcrabVolume volume, Runnable authorize) {
        if (volume.busy()) {
            throw new WorkerUnavailableException();
        }
        if (volume.state().equals("ready")) {
            authorize.run();
            volume = client.deleteVolume(volume);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!volume.state().equals("deleted")) {
            if (!volume.state().equals("deleting")) {
                throw new WorkerUnavailableException();
            }
            NativeRepositoryLease.pause(deadline, authorize);
            volume = client.volume(volume);
        }
        if (volume.busy()) {
            throw new WorkerUnavailableException();
        }
        return volume;
    }

    private void releaseReceipts(UUID copy, SandcrabVolume deleted) {
        try {
            removed.accept(copy);
        } catch (WorkerUnavailableException failure) {
            log.warn("Deleted copy artifact cleanup remains pending", failure);
        }
        try {
            client.forget(deleted);
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            log.warn("Deleted native volume receipt remains pending", failure);
        }
    }

    private void authorize(AuthPrincipal principal, WorkspaceId workspace, ExecutionCancellation cancellation) {
        requireOpen();
        if (cancellation.isCancelled()) {
            throw new WorkerUnavailableException();
        }
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new SecurityException("Repository disposal requires an API grant");
        }
        auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
    }

    private void requireOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new WorkerUnavailableException();
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
