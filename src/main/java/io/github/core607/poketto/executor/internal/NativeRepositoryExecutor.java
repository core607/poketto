package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/** Native repository execution keeps account ownership and remote-write recovery in the application. */
final class NativeRepositoryExecutor implements RepositoryExecutor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryExecutor.class);
    private static final Duration AGENT_CLAIM = Duration.ofMinutes(2);
    private final SandcrabClient client;
    private final AuthService auth;
    private final AccountCopyStore accounts;
    private final NativeRepositoryWorkspace workspaces;
    private final NativeRepositoryHost host;
    private final NativeArtifacts artifacts;
    private final CommandObserver observer;
    private final AccountOperations operations;
    private final Map<SiteGroup, Integer> quotas;
    private final Semaphore capacity;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    NativeRepositoryExecutor(
            SandcrabClient client,
            AuthService auth,
            AccountCopyStore accounts,
            NativeRepositoryWorkspace workspaces,
            NativeRepositoryHost host,
            NativeArtifacts artifacts,
            CommandObserver observer,
            ExecutionLimits limits) {
        this.client = client;
        this.auth = auth;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.host = host;
        this.artifacts = artifacts;
        this.observer = observer;
        operations = new AccountOperations(limits.accountOperations(), auth::siteGroup);
        quotas = limits.accountCopies();
        capacity = new Semaphore(Math.min(limits.maxSessions(), limits.maxOperations()));
    }

    void checkWorker() {
        workspaces.info();
    }

    @Override
    public ExecutionResult execute(
            AuthPrincipal principal,
            WorkspaceId workspace,
            CopyRequest expected,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        validate(expected, requested, command, timeout);
        if (cancellation.isCancelled()) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.BUSY, false);
        }
        var owner = owner(principal, workspace);
        try (AccountCommand held = command(owner, principal, expected, cancellation)) {
            try {
                requireCopy(held, expected);
                return executeHeld(principal, held, requested, command, timeout, cancellation);
            } catch (RuntimeException failure) {
                throw IsolatedRepositoryExecutor.admissionFailure(failure, held.record());
            }
        }
    }

    private AccountCopyRecord.Owner owner(AuthPrincipal principal, WorkspaceId workspace) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY) {
            throw new SecurityException("Repository execution requires an API grant");
        }
        var access = auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
        return new AccountCopyRecord.Owner(
                principal.accountId(), workspace.value(), access.capabilities().contains(Capability.READ_PRIVATE));
    }

    private static void validate(CopyRequest expected, Optional<String> commit, String source, Duration timeout) {
        if (expected == null || source == null || source.isBlank() || source.length() > 16384) {
            throw new IllegalArgumentException("Repository commands require a copy identity and bounded source");
        }
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException(
                    "Repository command timeout must be between one millisecond and 60 seconds");
        }
        commit.ifPresent(value -> ProtocolValues.hex(value, 40, "requested commit"));
    }

    private AccountCommand command(
            AccountCopyRecord.Owner owner,
            AuthPrincipal principal,
            CopyRequest expected,
            ExecutionCancellation cancellation) {
        if (!expected.id().equals(NEW_COPY)) {
            return new AccountCommand(accounts, accounts.ownerFor(owner, expected.id()), cancellation);
        }
        Optional<AccountCommand> idle = unclaimed(owner);
        if (idle.isPresent()) {
            return idle.get();
        }
        for (AccountCopyRecord.Owner fork : accounts.unclaimedForksOf(owner, AGENT_CLAIM)) {
            idle = unclaimed(fork);
            if (idle.isPresent()) {
                return idle.get();
            }
        }
        Integer quota = quotas.isEmpty() ? null : quotas.get(auth.siteGroup(principal));
        if (quota != null && accounts.copiesOf(owner.accountId(), owner.workspaceId()) >= quota) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, false);
        }
        return new AccountCommand(accounts, owner.forked(UUID.randomUUID()), cancellation);
    }

    private Optional<AccountCommand> unclaimed(AccountCopyRecord.Owner owner) {
        Optional<AccountCommand> candidate = AccountCommand.ifIdle(accounts, owner);
        if (candidate.isPresent()
                && candidate.get().record() != null
                && accounts.recentlyUsed(candidate.get().record(), AGENT_CLAIM)) {
            candidate.get().close();
            return Optional.empty();
        }
        return candidate;
    }

    private static void requireCopy(AccountCommand held, CopyRequest expected) {
        if (expected.id().equals(NEW_COPY)) {
            return;
        }
        if (held.record() == null || !held.record().copyId().toString().equals(expected.id())) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.MISSING_COPY, false);
        }
    }

    private ExecutionResult executeHeld(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        reconcile();
        AccountOperations.Slot account = acquire(principal);
        var running = new Active(principal, held.owner(), account);
        active.put(running.id, running);
        try (var registration = cancellation.onCancel(running::cancel)) {
            workspaces.open(principal, held, requested, cancellation, lease -> {
                running.lease = lease;
                if (closed.get() || running.cancelled) {
                    running.cancel();
                    throw new WorkerUnavailableException();
                }
            });
            NativeRepositoryLease lease = running.lease;
            lease.setup(NativeRepositoryCli.install());
            UUID execution = UUID.randomUUID();
            running.execution = execution;
            held.begin(execution);
            running.attempted = true;
            SandcrabProtocol.Run submitted = lease.command(execution, command, timeout);
            observer.observe(
                    submitted.reference(), timeout.plusSeconds(5), bytes -> host.reply(principal, held, lease, bytes));
            SandcrabProtocol.Run result = client.get(submitted.reference());
            if (!result.terminal()) {
                throw new WorkerUnavailableException();
            }
            lease.authorize();
            lease.close();
            workspaces.authorize(principal, held.owner(), held.record());
            NativeArtifacts.Output output = artifacts.output(
                    held.record(), result.result(), () -> workspaces.authorize(principal, held.owner(), held.record()));
            held.complete(held.record().state());
            ExecutionResult response = result(result, held, output);
            running.attempted = false;
            releaseReceipts(lease);
            workspaces.authorize(principal, held.owner(), held.record());
            return response;
        } catch (RuntimeException failure) {
            running.cancel();
            removeFailedArtifacts(held, running, failure);
            if (running.attempted) {
                throw new ExecutionUnconfirmedException(held.record().copyId().toString(), held.view(), true, failure);
            }
            throw failure;
        } finally {
            running.finished = true;
            release(running);
        }
    }

    private void removeFailedArtifacts(AccountCommand held, Active running, RuntimeException failure) {
        if (held.record() != null && running.execution != null) {
            try {
                artifacts.removeExecution(held.record().copyId(), running.execution);
            } catch (WorkerUnavailableException cleanup) {
                failure.addSuppressed(cleanup);
                log.warn("Failed command artifacts remain charged pending cleanup", cleanup);
            }
        }
    }

    private AccountOperations.Slot acquire(AuthPrincipal principal) {
        if (closed.get() || !capacity.tryAcquire()) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, false);
        }
        try {
            AccountOperations.Slot account = operations.acquire(principal, Duration.ofSeconds(5));
            if (account == null) {
                capacity.release();
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, false);
            }
            return account;
        } catch (InterruptedException interrupted) {
            capacity.release();
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        }
    }

    private static ExecutionResult result(
            SandcrabProtocol.Run run, AccountCommand held, NativeArtifacts.Output output) {
        SandcrabProtocol.Result value = run.result();
        TerminationReason reason =
                switch (run.state()) {
                    case "timed_out" -> TerminationReason.TIMEOUT;
                    case "output_limit" -> TerminationReason.OUTPUT_LIMIT;
                    case "resource_limit" -> TerminationReason.RESOURCE_LIMIT;
                    case "cancelled" -> TerminationReason.CANCELLED;
                    case "succeeded", "failed" ->
                        value.code() == null ? TerminationReason.NORMAL : TerminationReason.SANDBOX_FAILURE;
                    default -> TerminationReason.SANDBOX_FAILURE;
                };
        return new ExecutionResult(
                held.record().copyId().toString(),
                held.record().state().originalCommit(),
                value.exitCode() == null ? -1 : value.exitCode(),
                output.stdout(),
                output.stderr(),
                output.stdoutTruncated(),
                output.stderrTruncated(),
                reason == TerminationReason.TIMEOUT,
                true,
                false,
                reason,
                output.artifacts(),
                output.errors(),
                held.view());
    }

    private static void releaseReceipts(NativeRepositoryLease lease) {
        try {
            lease.releaseReceipts();
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            log.warn("Contained native repository receipts remain for reconciliation", failure);
        }
    }

    /** Retained admission is released only after a confirmed close or the daemon's changed epoch. */
    void reconcile() {
        artifacts.collect();
        for (Active running : List.copyOf(active.values())) {
            if (!running.finished) {
                continue;
            }
            NativeRepositoryLease lease = running.lease;
            if (lease != null
                    && !lease.writer()
                            .workerBootId()
                            .toString()
                            .equals(client.info().serverId())) {
                running.epochChanged = true;
            } else {
                running.cancel();
            }
            release(running);
        }
    }

    private void release(Active running) {
        if (running.finished
                && (running.lease == null || running.lease.contained() || running.epochChanged)
                && active.remove(running.id, running)) {
            running.account.close();
            capacity.release();
        }
    }

    @EventListener
    void revoked(AuthRevocation event) {
        active.values().stream()
                .filter(value ->
                        value.owner.workspaceId().equals(event.workspaceId().value()))
                .filter(value -> event.accountIds().contains(value.owner.accountId())
                        || event.apiKeyIds().contains(value.principal.subjectId()))
                .forEach(Active::cancel);
        artifacts.removeAccounts(event.workspaceId(), event.accountIds());
    }

    @Override
    public DiscardResult discard(
            AuthPrincipal principal,
            WorkspaceId workspace,
            DiscardRequest request,
            ExecutionCancellation cancellation) {
        owner(principal, workspace);
        var scope = new AccountCopyRecord.Owner(principal.accountId(), workspace.value(), true);
        AccountCopyRecord.Owner owner = accounts.ownerFor(scope, request.id());
        try (var held = new AccountCommand(accounts, owner, cancellation)) {
            if (held.record() == null || !held.record().copyId().toString().equals(request.id())) {
                return new DiscardResult(request.id(), DiscardStatus.ABSENT);
            }
            AccountCopyRecord record = held.record();
            if (record.writer().storage() == null) {
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, true);
            }
            workspaces.fence(record, workspaces.info());
            auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
            SandcrabVolume volume =
                    client.volume(record.writer().storage().volumeId().toString());
            record.writer().storage().requireVolume(volume);
            held.beginDiscard();
            volume = client.deleteVolume(volume);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!volume.state().equals("deleted")) {
                NativeRepositoryLease.pause(deadline, () -> {});
                volume = client.volume(volume);
            }
            held.remove();
            artifacts.removeCopy(record.copyId());
            client.forget(volume);
            return new DiscardResult(request.id(), DiscardStatus.DISCARDED);
        }
    }

    @Override
    public Optional<ArtifactChunk> readArtifact(
            AuthPrincipal principal,
            WorkspaceId workspace,
            String artifactId,
            long offset,
            int limit,
            ExecutionCancellation cancellation) {
        owner(principal, workspace);
        Optional<AccountCopyRecord.Owner> artifactOwner = artifacts.owner(principal, workspace, artifactId);
        if (artifactOwner.isEmpty()) {
            return Optional.empty();
        }
        try (var held = new AccountCommand(accounts, artifactOwner.get(), cancellation)) {
            if (held.record() == null || accounts.expired(held.record())) {
                return Optional.empty();
            }
            held.requireLive();
            Runnable authorize = () -> {
                if (closed.get() || cancellation.isCancelled()) {
                    throw new WorkerUnavailableException();
                }
                workspaces.authorize(principal, held.owner(), held.record());
            };
            authorize.run();
            Optional<ArtifactChunk> found = artifacts.read(held.record(), artifactId, offset, limit, authorize);
            if (found.isPresent()) {
                held.touch();
            }
            authorize.run();
            return found;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        active.values().forEach(Active::cancel);
        reconcile();
        artifacts.close();
    }

    @FunctionalInterface
    interface CommandObserver {
        void observe(SandcrabProtocol.Reference run, Duration timeout, Function<byte[], byte[]> handler);
    }

    private static final class Active {
        final UUID id = UUID.randomUUID();
        final AuthPrincipal principal;
        final AccountCopyRecord.Owner owner;
        final AccountOperations.Slot account;
        volatile NativeRepositoryLease lease;
        volatile boolean attempted;
        volatile boolean finished;
        volatile boolean epochChanged;
        volatile boolean cancelled;
        volatile UUID execution;

        Active(AuthPrincipal principal, AccountCopyRecord.Owner owner, AccountOperations.Slot account) {
            this.principal = principal;
            this.owner = owner;
            this.account = account;
        }

        void cancel() {
            cancelled = true;
            if (lease != null) {
                try {
                    lease.close();
                } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
                    log.warn("Native repository containment remains unconfirmed", failure);
                }
            }
        }
    }
}
