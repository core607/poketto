package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** New Git workspaces share commit templates. Imported volumes keep their original storage binding. */
final class NativeRepositoryWorkspace {
    static final UUID PENDING_WORKSPACE = new UUID(0, 0);
    private final SandcrabClient client;
    private final SandcrabWorkspaces keyed;
    private final AuthService auth;
    private final RepositorySnapshotExports exports;
    private final SelectedFileSaves saves;
    private final AccountCopyStore accounts;
    private final NativeRepositoryOverlays overlays;
    private final NativeRepositoryProfiles profiles;
    private final long bytes;
    private final long inodes;
    private final UUID application = UUID.randomUUID();

    NativeRepositoryWorkspace(
            SandcrabClient client,
            AuthService auth,
            RepositorySnapshotExports exports,
            SelectedFileSaves saves,
            AccountCopyStore accounts,
            NativeRepositoryOverlays overlays,
            NativeRepositoryProfiles profiles,
            long bytes,
            long inodes) {
        this.client = client;
        keyed = client.workspaces();
        this.auth = auth;
        this.exports = exports;
        this.saves = saves;
        this.accounts = accounts;
        this.overlays = overlays;
        this.profiles = profiles;
        this.bytes = bytes;
        this.inodes = inodes;
    }

    SandcrabProtocol.Info info() {
        SandcrabProtocol.Info info = client.info();
        profiles.offlinePreparation(info);
        SandcrabProtocol.Profile execution = info.profiles().get(profiles.execution());
        if (!info.capabilities().containsAll(Set.of("workspaces", "sessions", "templates", "files", "uploads", "calls"))
                || execution == null
                || !execution.networkDisabled()
                || !execution.hostCalls()) {
            throw new WorkerUnavailableException();
        }
        return info;
    }

    void authorize(AuthPrincipal principal, AccountCopyRecord.Owner owner, AccountCopyRecord record) {
        if (principal.kind() != AuthPrincipal.Kind.API_KEY
                || !principal.accountId().equals(owner.accountId())) {
            throw new SecurityException("Repository execution requires the account's API grant");
        }
        var workspace = new WorkspaceId(owner.workspaceId());
        if (owner.fullRead()) {
            auth.authorize(principal, workspace, Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY);
        } else if (record != null) {
            if (exports.publicProjectionChanged(principal, workspace, record.publicExport())) {
                throw ExecutionAdmissionException.publicationChanged(
                        record.copyId().toString());
            }
        } else {
            auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
        }
    }

    private static AccountCopyRecord retained(AccountCommand held, Optional<String> requested) {
        held.requireLive();
        AccountCopyRecord record = held.record();
        if (record.writer().storage() == null || record.phase() == AccountCopyRecord.Phase.INITIALIZING) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, true);
        }
        if (requested.isPresent() && !requested.get().equals(record.state().baseCommit())) {
            throw new IllegalArgumentException("The requested commit differs from the acknowledged copy baseline");
        }
        return record;
    }

    void openWorkspace(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            SandcrabProtocol.Info info,
            Runnable authorize,
            Consumer<NativeRepositoryLease> admitted) {
        authorize.run();
        UUID copy =
                held.record() == null ? held.owner().newCopyId() : held.record().copyId();
        boolean created = held.record() == null;
        if (created) {
            initialize(principal, held, copy, requested, info, authorize);
        }
        var record = created ? held.record() : retained(held, requested);
        var storage = record.writer().storage();
        if (!created && rawWriter(record.writer())) {
            fence(record, info);
        }
        var found = keyed.find(copy);
        found.ifPresent(handle -> SandcrabWorkspaces.scope(handle, copy, storage));
        boolean restore = storage.workspaceGeneration() == null
                || found.isEmpty()
                || record.phase() != AccountCopyRecord.Phase.READY;
        SandcrabWorkspaces.Handle handle;
        if (restore) {
            // Never expose a partial restore or a write the account journal did not acknowledge.
            if (found.isPresent()) {
                keyed.remove(found.get(), authorize);
            }
            var pending = writer(principal, info, storage.generation(null), PENDING_WORKSPACE);
            held.attach(pending);
            if (!created && rawWriter(record.writer())) {
                forgetFenced(record.writer(), info);
            }
            handle = keyed.ensure(copy, pending.storage(), authorize);
        } else {
            handle = found.orElseThrow();
            if (!handle.state().equals("ready")) {
                throw new WorkerUnavailableException();
            }
        }
        if (!handle.serverId().equals(info.serverId())) {
            throw new WorkerUnavailableException(
                    new IllegalStateException("Native service restarted during workspace attachment"), false);
        }
        var generation = UUID.fromString(handle.generation());
        var owner = writer(principal, info, storage.generation(generation), generation);
        var lease = lease(owner, info, authorize);
        lease.workspace(handle);
        admitted.accept(lease);
        if (restore) {
            held.attach(owner);
            if (storage.upper() != null) {
                overlays.restore(held, lease);
            }
        }
        held.bind(owner);
    }

    private static boolean rawWriter(AccountCopyRecord.Writer writer) {
        return writer.storage().workspaceGeneration() == null && !PENDING_WORKSPACE.equals(writer.leaseId());
    }

    void removeWorkspace(AccountCopyRecord record, Runnable authorize) {
        keyed.find(record.copyId()).ifPresent(handle -> {
            SandcrabWorkspaces.scope(handle, record.copyId(), record.writer().storage());
            keyed.remove(handle, authorize);
        });
    }

    private AccountCopyRecord.Writer writer(
            AuthPrincipal principal,
            SandcrabProtocol.Info info,
            AccountCopyRecord.NativeStorage storage,
            UUID leaseId) {
        return new AccountCopyRecord.Writer(
                principal.subjectId(), UUID.fromString(info.serverId()), application, leaseId, storage);
    }

    NativeRepositoryPool.Claim claimVolume(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            NativeRepositoryOperation running,
            SandcrabProtocol.Info admittedInfo,
            NativeRepositoryPool pool) {
        UUID copy = held.record().copyId();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (running.cancelled) {
                throw new WorkerUnavailableException();
            }
            SandcrabProtocol.Info info = attempt == 0 ? admittedInfo : info();
            var claim = pool.claim(principal, held.owner(), copy, held.record(), info);
            running.claim = claim;
            try {
                openVolume(principal, held, requested, cancellation, info, claim, lease -> {
                    running.lease = lease;
                    if (running.cancelled) {
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

    private NativeRepositoryLease openVolume(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            SandcrabProtocol.Info info,
            NativeRepositoryPool.Claim claim,
            Consumer<NativeRepositoryLease> admitted) {
        authorize(principal, held.owner(), held.record());
        AccountCopyRecord retained = retained(held, requested);
        AccountCopyRecord.NativeStorage storage = retained.writer().storage();
        if (claim.fresh()) {
            fence(retained, info);
            if (!info.capabilities().contains("volumes")
                    || info.storage() == null
                    || !Boolean.TRUE.equals(info.storage().quotaEnforced())) {
                throw new WorkerUnavailableException();
            }
            storage.requireVolume(client.volume(storage.volumeId().toString()));
        }
        var owner = claim.fresh() ? writer(principal, info, storage, UUID.randomUUID()) : claim.writer();
        var lease = lease(owner, info, () -> {
            claim.requireUsable();
            if (cancellation.isCancelled()) {
                throw new WorkerUnavailableException();
            }
            authorize(principal, held.owner(), held.record());
        });
        admitted.accept(lease);
        if (claim.fresh()) {
            held.attach(lease.writer());
            try {
                forgetFenced(retained.writer(), info);
                openRegistered(lease, held, claim);
            } catch (RuntimeException failure) {
                if (!lease.mayHaveExecuted()) {
                    held.attachmentRefused(retained.writer());
                }
                throw failure;
            }
            lease.setup(NativeRepositoryLayout.attach());
            held.bind(lease.writer());
        } else {
            held.bind(lease.writer());
            if (!lease.resume(claim.session())) {
                throw new NativeRepositoryLease.Ended();
            }
        }
        return lease;
    }

    private void initialize(
            AuthPrincipal principal,
            AccountCommand held,
            UUID copyId,
            Optional<String> requested,
            SandcrabProtocol.Info info,
            Runnable authorizeScope) {
        var workspace = new WorkspaceId(held.owner().workspaceId());
        if (!held.owner().fullRead() && requested.isPresent()) {
            throw new IllegalArgumentException("Public execution starts from current publication; omit commit");
        }
        RepositorySnapshotExports.PublicExport projection =
                held.owner().fullRead() ? null : exports.createPublic(principal, workspace);
        RepositorySnapshotExports.Export export =
                projection == null ? exports.create(principal, workspace, requested) : projection.export();
        try (var release = new ExportRelease(exports, export.exportId())) {
            Runnable verify = () -> {
                authorizeScope.run();
                if (projection != null) {
                    exports.requireCurrentPublic(principal, workspace, projection);
                }
            };
            var source = new AccountTemplateCatalog.Source(
                    workspace.value(), held.owner().fullRead(), export.commit(), profiles.execution());
            overlays.bind(
                    source,
                    info,
                    export,
                    verify,
                    with -> exports.withBundle(principal, workspace, export, with),
                    template -> {
                        var storage = new AccountCopyRecord.NativeStorage(
                                null, profiles.execution(), bytes, inodes, template, null);
                        held.initialize(
                                copyId,
                                writer(principal, info, storage, PENDING_WORKSPACE),
                                new SelectedFileSaves.State(export.commit()).snapshot(),
                                projection,
                                sink -> saves.visitOriginal(
                                        principal, workspace, export.commit(), accounts.traversalLimits(), sink));
                    });
        }
    }

    void retireTemplate(String id) {
        overlays.retire(id);
    }

    void checkpoint(AccountCommand held, NativeRepositoryLease lease) {
        overlays.checkpoint(held, lease);
    }

    private record ExportRelease(RepositorySnapshotExports exports, UUID id) implements AutoCloseable {
        @Override
        public void close() {
            exports.release(id);
        }
    }

    private NativeRepositoryLease lease(
            AccountCopyRecord.Writer writer, SandcrabProtocol.Info info, Runnable authorize) {
        SandcrabProtocol.Profile settings = info.profiles().get(writer.storage().profile());
        if (settings == null || !settings.hostCalls()) {
            throw new WorkerUnavailableException();
        }
        return new NativeRepositoryLease(
                client,
                writer.storage(),
                writer,
                settings.sessionTtlMillis(),
                settings.outputLimitBytes(),
                settings.transferBytes(),
                settings.timeoutMillis(),
                authorize);
    }

    private static void openRegistered(
            NativeRepositoryLease lease, AccountCommand held, NativeRepositoryPool.Claim claim) {
        claim.register(lease.plan(), held.record());
        boolean ready = false;
        try {
            lease.open();
            claim.ready(lease.session(), held.record());
            ready = true;
        } finally {
            if (!ready) {
                claim.openFailed(lease.mayHaveExecuted());
            }
        }
    }

    /** The journal already points to the replacement, so loss of the previous terminal receipt is harmless. */
    private void forgetFenced(AccountCopyRecord.Writer previous, SandcrabProtocol.Info info) {
        if (!previous.workerBootId().toString().equals(info.serverId())) {
            return;
        }
        var reference = new SandcrabProtocol.Reference(previous.leaseId().toString(), info.serverId());
        try {
            client.forget(client.session(reference));
        } catch (SandcrabClient.Rejected absent) {
            if (!GameSandboxJob.missing(absent, "SESSION_NOT_FOUND")) {
                throw absent;
            }
        }
    }

    /** Missing sessions prove containment only after durable ready acknowledgement; unknown creates remain fenced. */
    void fence(AccountCopyRecord record, SandcrabProtocol.Info info) {
        if (!rawWriter(record.writer())
                || !record.writer().workerBootId().toString().equals(info.serverId())) {
            return;
        }
        var reference = new SandcrabProtocol.Reference(record.writer().leaseId().toString(), info.serverId());
        try {
            NativeRepositoryLease.close(client, reference, Duration.ofSeconds(15));
        } catch (SandcrabClient.Rejected missing) {
            boolean acknowledged = Set.of(
                            AccountCopyRecord.Phase.READY,
                            AccountCopyRecord.Phase.RUNNING,
                            AccountCopyRecord.Phase.INTERRUPTED,
                            AccountCopyRecord.Phase.DISCARDING)
                    .contains(record.phase());
            if (!GameSandboxJob.missing(missing, "SESSION_NOT_FOUND") || !acknowledged) {
                throw missing;
            }
        }
    }
}
