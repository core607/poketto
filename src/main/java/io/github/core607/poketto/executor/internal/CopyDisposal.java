package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor.CopyRequest;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardRequest;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardResult;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardStatus;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Removing an account's disk copy: the client's explicit discard, the maintenance pass that
 * collects expired copies, and the fencing that terminates a retained lease before its files go.
 * Every path takes the account journal first, marks the copy discarding and only then asks the
 * worker to release it, so an interruption between the two leaves a copy a later pass collects.
 */
final class CopyDisposal {
    private static final Logger log = LoggerFactory.getLogger(CopyDisposal.class);

    private final SessionRegistry registry;
    private final SessionWorker io;
    private final SessionLifecycle lifecycle;
    private final WorkerClient worker;
    private final AccountCopyStore accounts;
    private final Duration closeTimeout;

    CopyDisposal(
            SessionRegistry registry,
            SessionWorker io,
            SessionLifecycle lifecycle,
            WorkerClient worker,
            AccountCopyStore accounts,
            Duration closeTimeout) {
        this.registry = registry;
        this.io = io;
        this.lifecycle = lifecycle;
        this.worker = worker;
        this.accounts = accounts;
        this.closeTimeout = closeTimeout;
    }

    DiscardResult discard(
            AuthPrincipal principal,
            WorkspaceId workspace,
            DiscardRequest request,
            ExecutionCancellation cancellation) {
        requireAuthorization(principal, workspace, cancellation);
        // Cleanup remains available when read authority has been withdrawn; no content is returned.
        for (boolean full : List.of(true, false)) {
            var owner = new AccountCopyRecord.Owner(principal.accountId(), workspace.value(), full);
            try (var held = new AccountCommand(accounts, owner, cancellation)) {
                var record = held.record();
                if (record == null || !record.copyId().toString().equals(request.id())) {
                    continue;
                }
                if (record.phase() != AccountCopyRecord.Phase.DISCARDING) {
                    registry.requireCopyMatch(record, new CopyRequest(request.id()));
                }
                held.beginDiscard();
                var key = new ExecutionSession.Key(
                        principal.accountId(), workspace, SessionWorker.hash(full ? "full" : "public"));
                var hello = worker.hello();
                lifecycle.fenceAccount(principal, record, key, hello);
                requireAuthorization(principal, workspace, cancellation);
                discardDisk(principal.subjectId(), record, key, hello);
                held.remove();
                return new DiscardResult(request.id(), DiscardStatus.DISCARDED);
            } catch (RuntimeException failure) {
                throw IsolatedRepositoryExecutor.admissionFailure(failure, null);
            }
        }
        return new DiscardResult(request.id(), DiscardStatus.ABSENT);
    }

    void discardDisk(UUID principalId, AccountCopyRecord record, ExecutionSession.Key key, WorkerClient.Hello hello) {
        var identity = new WorkerClient.Identity(
                principalId, key.account(), key.workspace().value(), key.scopeHash(), UUID.randomUUID());
        JsonNode response = worker.request(
                hello,
                identity,
                "DISCARD",
                new WorkerRequests.DiskCopy(
                        record.copyId(),
                        record.owner().fullRead() ? "full" : "public",
                        record.state().originalCommit()),
                closeTimeout);
        if (!response.path("ok").asBoolean()
                || !identity.leaseId()
                        .toString()
                        .equals(response.path("leaseId").asString())
                || !Set.of("DISCARDED", "ABSENT")
                        .contains(response.path("state").asString(""))) {
            throw new WorkerUnavailableException();
        }
    }

    int collectExpiredCopies() {
        int removed = 0;
        for (var owner : accounts.collectionCandidates(8)) {
            if (registry.isClosed() || Thread.currentThread().isInterrupted()) {
                break;
            }
            try (var lease = accounts.acquire(owner)) {
                var record = lease.record().orElse(null);
                if (record == null || !accounts.collectible(record)) {
                    continue;
                }
                if (record.phase() != AccountCopyRecord.Phase.DISCARDING) {
                    lease.write(record.discarding());
                }
                var key = new ExecutionSession.Key(
                        owner.accountId(),
                        new WorkspaceId(owner.workspaceId()),
                        SessionWorker.hash(owner.fullRead() ? "full" : "public"));
                var hello = worker.hello();
                fenceCollectedAccount(record, key, hello);
                discardDisk(record.writer().principalId(), record, key, hello);
                lease.remove(record.copyId());
                removed++;
            } catch (RetainedCopyException busy) {
                if (busy.reason() != RetainedCopyException.Reason.BUSY) {
                    log.warn("Account copy collection will retry", busy);
                }
            } catch (IOException | WorkerUnavailableException unavailable) {
                log.warn("Account copy collection awaits confirmed cleanup", unavailable);
            }
        }
        return removed;
    }

    private void fenceCollectedAccount(AccountCopyRecord record, ExecutionSession.Key key, WorkerClient.Hello hello) {
        ExecutionSession current = registry.session(key);
        if (current != null && current.copyId.equals(record.copyId())) {
            lifecycle.fenceAccount(current.principal, record, key, hello);
            return;
        }
        if (!record.writer().workerBootId().equals(hello.workerBootId())) {
            return;
        }
        var identity = new WorkerClient.Identity(
                record.writer().principalId(),
                key.account(),
                key.workspace().value(),
                key.scopeHash(),
                record.writer().leaseId());
        long deadline = System.nanoTime() + closeTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = worker.closeRetainedLease(
                    hello, identity, record.writer().appBootId(), "session_closed", Duration.ofSeconds(3));
            if (!response.path("ok").asBoolean()
                    || !identity.leaseId()
                            .toString()
                            .equals(response.path("leaseId").asString())) {
                throw new WorkerUnavailableException();
            }
            String state = response.path("state").asString("");
            if (state.equals("CLOSED")) {
                return;
            }
            if (!state.equals("CLOSING")) {
                throw new WorkerUnavailableException();
            }
            SessionLifecycle.pause();
        }
        throw new WorkerUnavailableException();
    }

    void requireAuthorization(AuthPrincipal principal, WorkspaceId workspace, ExecutionCancellation cancellation) {
        io.authorize(principal, workspace);
        if (registry.isClosed() || cancellation.isCancelled()) {
            throw new WorkerUnavailableException();
        }
    }
}
