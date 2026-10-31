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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
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
    private final NativeRepositoryPool pool;
    private final NativeRepositoryDisposal disposal;
    private final CommandObserver observer;
    private final AccountOperations operations;
    private final Map<SiteGroup, Integer> quotas;
    private final Semaphore capacity;
    private final int maxOperations;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
            .daemon()
            .name("poketto-native-repository-heartbeat")
            .factory());

    NativeRepositoryExecutor(
            SandcrabClient client,
            AuthService auth,
            AccountCopyStore accounts,
            NativeRepositoryWorkspace workspaces,
            NativeRepositoryHost host,
            NativeArtifacts artifacts,
            CommandObserver observer,
            ExecutionLimits limits) {
        this(client, auth, accounts, workspaces, host, artifacts, observer, limits, System::nanoTime);
    }

    NativeRepositoryExecutor(
            SandcrabClient client,
            AuthService auth,
            AccountCopyStore accounts,
            NativeRepositoryWorkspace workspaces,
            NativeRepositoryHost host,
            NativeArtifacts artifacts,
            CommandObserver observer,
            ExecutionLimits limits,
            LongSupplier clock) {
        this.client = client;
        this.auth = auth;
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.host = host;
        this.artifacts = artifacts;
        pool = new NativeRepositoryPool(
                client,
                limits,
                binding ->
                        workspaces.authorize(binding.principal(), binding.key().owner(), binding.record()),
                clock);
        disposal = new NativeRepositoryDisposal(client, auth, accounts, pool, workspaces, artifacts::removeCopy);
        this.observer = observer;
        operations = new AccountOperations(limits.accountOperations(), auth::siteGroup);
        quotas = limits.accountCopies();
        capacity = new Semaphore(limits.maxOperations());
        maxOperations = limits.maxOperations();
        heartbeat.scheduleWithFixedDelay(this::maintainDue, 250, 250, TimeUnit.MILLISECONDS);
    }

    void checkWorker() {
        if (closed.get()) {
            throw new WorkerUnavailableException();
        }
        workspaces.info();
    }

    void bindMetrics(MeterRegistry registry) {
        pool.metrics.bind(registry, pool::size, () -> maxOperations - capacity.availablePermits());
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
        ExecutionResult completed = null;
        try (AccountCommand held = admittedCommand(owner, principal, expected, cancellation)) {
            try {
                requireCopy(held, expected);
                completed = executeHeld(principal, held, requested, command, timeout, cancellation);
                return completed;
            } catch (RuntimeException failure) {
                throw IsolatedRepositoryExecutor.admissionFailure(failure, held.record());
            }
        } catch (RetainedCopyException failure) {
            if (completed == null) {
                throw failure;
            }
            log.warn("Completed native command journal could not be released", failure);
            throw new ExecutionUnconfirmedException(completed.copyId(), completed.retention(), true, failure);
        }
    }

    private AccountCommand admittedCommand(
            AccountCopyRecord.Owner owner,
            AuthPrincipal principal,
            CopyRequest expected,
            ExecutionCancellation cancellation) {
        try {
            return command(owner, principal, expected, cancellation);
        } catch (RetainedCopyException failure) {
            if (failure.reason() == RetainedCopyException.Reason.BUSY) {
                pool.metrics.refused("session_busy");
            } else if (failure.reason() == RetainedCopyException.Reason.LIMIT) {
                pool.metrics.refused("copy_limit");
            }
            throw IsolatedRepositoryExecutor.admissionFailure(failure, null);
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
        ProtocolValues.withoutNul(source, "command source");
    }

    private AccountCommand command(
            AccountCopyRecord.Owner owner,
            AuthPrincipal principal,
            CopyRequest expected,
            ExecutionCancellation cancellation) {
        if (!expected.id().equals(NEW_COPY)) {
            return new AccountCommand(accounts, accounts.ownerFor(owner, expected.id()), cancellation);
        }
        Optional<AccountCommand> idle = unclaimed(principal, owner);
        if (idle.isPresent()) {
            return idle.get();
        }
        for (AccountCopyRecord.Owner fork : accounts.unclaimedForksOf(owner, AGENT_CLAIM)) {
            idle = unclaimed(principal, fork);
            if (idle.isPresent()) {
                return idle.get();
            }
        }
        requireCopyCapacity(principal, owner);
        return new AccountCommand(accounts, owner.forked(UUID.randomUUID()), cancellation);
    }

    private Optional<AccountCommand> unclaimed(AuthPrincipal principal, AccountCopyRecord.Owner owner) {
        Optional<AccountCommand> candidate = AccountCommand.ifIdle(accounts, owner);
        if (candidate.isEmpty()) {
            return candidate;
        }
        AccountCommand held = candidate.get();
        boolean selected = false;
        try {
            AccountCopyRecord record = held.record();
            if (record == null) {
                requireCopyCapacity(principal, owner);
            } else {
                try {
                    workspaces.authorize(principal, owner, record);
                } catch (ExecutionAdmissionException changed) {
                    if (changed.reason() != ExecutionAdmissionException.Reason.PUBLICATION_CHANGED) {
                        throw changed;
                    }
                    pool.closeCopy(record.copyId());
                    return Optional.empty();
                }
                if (accounts.recentlyUsed(record, AGENT_CLAIM)) {
                    return Optional.empty();
                }
            }
            selected = true;
            return candidate;
        } finally {
            if (!selected) {
                held.close();
            }
        }
    }

    private void requireCopyCapacity(AuthPrincipal principal, AccountCopyRecord.Owner owner) {
        Integer quota = quotas.isEmpty() ? null : quotas.get(auth.siteGroup(principal));
        if (quota != null && accounts.copiesOf(owner.accountId(), owner.workspaceId()) >= quota) {
            pool.metrics.refused("copy_limit");
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, false);
        }
    }

    private void requireCopy(AccountCommand held, CopyRequest expected) {
        if (expected.id().equals(NEW_COPY)) {
            return;
        }
        if (held.record() == null || !held.record().copyId().toString().equals(expected.id())) {
            pool.metrics.refused("copy_mismatch");
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
        SandcrabProtocol.Info info = reconcile();
        AccountOperations.Slot account = acquire(principal);
        var running = new Active(principal, held.owner(), account);
        active.put(running.id, running);
        try (var registration = cancellation.onCancel(running::cancel);
                var claim = open(principal, held, requested, cancellation, running, info)) {
            return run(principal, held, command, timeout, running, claim);
        } catch (RuntimeException failure) {
            if (!running.reusableRefusal) {
                running.cancel();
            }
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

    private NativeRepositoryPool.Claim open(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            Active running,
            SandcrabProtocol.Info admittedInfo) {
        UUID copy =
                held.record() == null ? held.owner().newCopyId() : held.record().copyId();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (closed.get() || running.cancelled) {
                throw new WorkerUnavailableException();
            }
            SandcrabProtocol.Info info = attempt == 0 ? admittedInfo : workspaces.info();
            var claim = pool.claim(principal, held.owner(), copy, held.record(), info);
            running.claim = claim;
            try {
                workspaces.open(principal, held, copy, requested, cancellation, info, claim, lease -> {
                    running.lease = lease;
                    if (closed.get() || running.cancelled) {
                        running.cancel();
                        throw new WorkerUnavailableException();
                    }
                });
                if (claim.fresh()) {
                    running.lease.setup(NativeRepositoryCli.install());
                }
                return claim;
            } catch (NativeRepositoryLease.Ended ended) {
                claim.invalidate();
                if (attempt != 0) {
                    throw ended;
                }
            } catch (RuntimeException failure) {
                try {
                    claim.close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        throw new WorkerUnavailableException();
    }

    private ExecutionResult run(
            AuthPrincipal principal,
            AccountCommand held,
            String command,
            Duration timeout,
            Active running,
            NativeRepositoryPool.Claim claim) {
        NativeRepositoryLease lease = running.lease;
        UUID execution = UUID.randomUUID();
        running.execution = execution;
        held.begin(execution);
        running.attempted = true;
        SandcrabProtocol.Run submitted = submit(lease, held, running, claim, execution, command, timeout);
        observer.observe(
                submitted.reference(), timeout.plusSeconds(5), bytes -> host.reply(principal, held, lease, bytes));
        SandcrabProtocol.Run completed = client.get(submitted.reference());
        if (!completed.terminal()) {
            throw new WorkerUnavailableException();
        }
        running.operationComplete = true;
        lease.authorize();
        SandcrabProtocol.Session observed = lease.inspect();
        boolean keep = observed.state().equals("ready")
                && completed.result().code() == null
                && (completed.state().equals("succeeded") || completed.state().equals("failed"));
        if (!keep) {
            lease.close();
        }
        workspaces.authorize(principal, held.owner(), held.record());
        NativeArtifacts.Output output = artifacts.output(
                held.record(), completed.result(), () -> workspaces.authorize(principal, held.owner(), held.record()));
        held.complete(held.record().state());
        releaseReceipts(lease, keep);
        workspaces.authorize(principal, held.owner(), held.record());
        if (keep) {
            claim.retain(held.record(), observed);
        }
        running.attempted = false;
        return NativeRepositoryResults.create(completed, held, output, claim.fresh());
    }

    private SandcrabProtocol.Run submit(
            NativeRepositoryLease lease,
            AccountCommand held,
            Active running,
            NativeRepositoryPool.Claim claim,
            UUID execution,
            String command,
            Duration timeout) {
        try {
            return lease.command(execution, command, timeout);
        } catch (SandcrabClient.Rejected refused) {
            if (refused.code().equals("ID_CONFLICT")) {
                throw refused;
            }
            held.refused();
            running.attempted = false;
            running.operationComplete = true;
            lease.authorize();
            SandcrabProtocol.Session observed = lease.inspect();
            if (observed.state().equals("ready")) {
                releaseReceipts(lease, true);
                claim.retain(held.record(), observed);
                running.reusableRefusal = true;
            }
            ExecutionAdmissionException.Reason reason = refused.status() == 429
                    ? ExecutionAdmissionException.Reason.CAPACITY
                    : ExecutionAdmissionException.Reason.UNAVAILABLE;
            throw new ExecutionAdmissionException(reason, true, refused);
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
            pool.metrics.refused("operation_limit");
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, false);
        }
        boolean acquired = false;
        try {
            AccountOperations.Slot account = operations.acquire(principal, Duration.ofSeconds(5));
            if (account == null) {
                pool.metrics.refused("account_limit");
                throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.CAPACITY, false);
            }
            acquired = true;
            return account;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        } finally {
            if (!acquired) {
                capacity.release();
            }
        }
    }

    private static void releaseReceipts(NativeRepositoryLease lease, boolean retainSession) {
        try {
            lease.releaseReceipts(retainSession);
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            log.warn("Contained native repository receipts remain for reconciliation", failure);
        }
    }

    /** Retained admission is released only after a confirmed close or the daemon's changed epoch. */
    SandcrabProtocol.Info reconcile() {
        artifacts.collect();
        SandcrabProtocol.Info info = workspaces.info();
        pool.maintain(info);
        for (Active running : List.copyOf(active.values())) {
            if (!running.finished) {
                continue;
            }
            release(running);
        }
        return info;
    }

    private void release(Active running) {
        if (running.finished
                && (running.operationComplete || running.claim == null || running.claim.contained())
                && active.remove(running.id, running)) {
            running.account.close();
            capacity.release();
        }
    }

    @EventListener
    void revoked(AuthRevocation event) {
        pool.revoked(event);
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
        return disposal.discard(principal, workspace, request, cancellation);
    }

    int collectExpiredCopies() {
        artifacts.collect();
        return disposal.collectExpiredCopies();
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
        disposal.close();
        heartbeat.shutdownNow();
        active.values().forEach(Active::cancel);
        try (artifacts) {
            pool.close();
            active.values().forEach(this::release);
        }
    }

    private void maintainDue() {
        try {
            if (!closed.get() && pool.maintenanceDue()) {
                pool.maintain(workspaces.info(), false);
                active.values().forEach(this::release);
            }
        } catch (RuntimeException failure) {
            pool.deferMaintenance();
            log.warn("Native repository maintenance awaits confirmed authority or containment", failure);
        }
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
        volatile NativeRepositoryPool.Claim claim;
        volatile boolean attempted;
        volatile boolean finished;
        volatile boolean operationComplete;
        volatile boolean reusableRefusal;
        volatile boolean cancelled;
        volatile UUID execution;

        Active(AuthPrincipal principal, AccountCopyRecord.Owner owner, AccountOperations.Slot account) {
            this.principal = principal;
            this.owner = owner;
            this.account = account;
        }

        void cancel() {
            cancelled = true;
            if (claim != null) {
                try {
                    claim.invalidate();
                } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
                    log.warn("Native repository containment remains unconfirmed", failure);
                }
            }
        }
    }
}
