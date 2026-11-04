package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** New Git workspaces share commit templates. Imported volumes keep their original storage binding. */
final class NativeRepositoryWorkspace {
    private final SandcrabClient client;
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
        if (!info.capabilities().containsAll(Set.of("sessions", "templates", "files", "uploads", "calls"))
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

    NativeRepositoryLease open(
            AuthPrincipal principal,
            AccountCommand held,
            UUID copyId,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            SandcrabProtocol.Info info,
            NativeRepositoryPool.Claim claim,
            Consumer<NativeRepositoryLease> admitted) {
        authorize(principal, held.owner(), held.record());
        if (held.record() == null) {
            return initialize(principal, held, copyId, requested, cancellation, info, claim, admitted);
        }
        AccountCopyRecord retained = held.record();
        held.requireLive();
        if (retained.writer().storage() == null || retained.phase() == AccountCopyRecord.Phase.INITIALIZING) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, true);
        }
        if (requested.isPresent() && !requested.get().equals(retained.state().baseCommit())) {
            throw new IllegalArgumentException("The requested commit differs from the acknowledged copy baseline");
        }
        AccountCopyRecord.NativeStorage storage = retained.writer().storage();
        if (claim.fresh()) {
            fence(retained, info);
            if (storage.volumeId() != null) {
                if (!info.capabilities().contains("volumes")
                        || info.storage() == null
                        || !Boolean.TRUE.equals(info.storage().quotaEnforced())) {
                    throw new WorkerUnavailableException();
                }
                storage.requireVolume(client.volume(storage.volumeId().toString()));
            }
        }
        var lease = lease(principal, held, storage, info, cancellation, claim);
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
            if (lease.overlay()) {
                overlays.restore(held, lease);
            } else {
                lease.setup(NativeRepositoryLayout.attach());
            }
            held.bind(lease.writer());
        } else {
            held.bind(lease.writer());
            if (!lease.resume(claim.session())) {
                throw new NativeRepositoryLease.Ended();
            }
        }
        return lease;
    }

    private NativeRepositoryLease initialize(
            AuthPrincipal principal,
            AccountCommand held,
            UUID copyId,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            SandcrabProtocol.Info info,
            NativeRepositoryPool.Claim claim,
            Consumer<NativeRepositoryLease> admitted) {
        var workspace = new WorkspaceId(held.owner().workspaceId());
        if (!held.owner().fullRead() && requested.isPresent()) {
            throw new IllegalArgumentException("Public execution starts from current publication; omit commit");
        }
        RepositorySnapshotExports.PublicExport projection =
                held.owner().fullRead() ? null : exports.createPublic(principal, workspace);
        RepositorySnapshotExports.Export export =
                projection == null ? exports.create(principal, workspace, requested) : projection.export();
        try (var release = new ExportRelease(exports, export.exportId())) {
            var ready = new AtomicReference<NativeRepositoryLease>();
            Runnable verify = () -> {
                claim.requireUsable();
                if (cancellation.isCancelled()) {
                    throw new WorkerUnavailableException();
                }
                authorize(principal, held.owner(), null);
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
                        var lease = lease(principal, held, storage, info, cancellation, claim);
                        admitted.accept(lease);
                        held.initialize(
                                copyId,
                                lease.writer(),
                                new SelectedFileSaves.State(export.commit()).snapshot(),
                                projection,
                                sink -> saves.visitOriginal(
                                        principal, workspace, export.commit(), accounts.traversalLimits(), sink),
                                AccountCopyRecord.Phase.INITIALIZING);
                        ready.set(lease);
                    });
            var lease = Objects.requireNonNull(ready.get());
            openRegistered(lease, held, claim);
            held.bind(lease.writer());
            return lease;
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
            AuthPrincipal principal,
            AccountCommand held,
            AccountCopyRecord.NativeStorage storage,
            SandcrabProtocol.Info info,
            ExecutionCancellation cancellation,
            NativeRepositoryPool.Claim claim) {
        SandcrabProtocol.Profile settings = info.profiles().get(storage.profile());
        if (settings == null || !settings.hostCalls()) {
            throw new WorkerUnavailableException();
        }
        AccountCopyRecord.Writer writer = claim.fresh()
                ? new AccountCopyRecord.Writer(
                        principal.subjectId(),
                        UUID.fromString(info.serverId()),
                        application,
                        UUID.randomUUID(),
                        storage)
                : claim.writer();
        return new NativeRepositoryLease(
                client,
                storage,
                writer,
                settings.sessionTtlMillis(),
                settings.outputLimitBytes(),
                settings.transferBytes(),
                settings.timeoutMillis(),
                () -> {
                    claim.requireUsable();
                    if (cancellation.isCancelled()) {
                        throw new WorkerUnavailableException();
                    }
                    authorize(principal, held.owner(), held.record());
                });
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
        if (!record.writer().workerBootId().toString().equals(info.serverId())) {
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
