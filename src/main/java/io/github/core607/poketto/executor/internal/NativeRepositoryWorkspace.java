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

/** Poketto initializes Git inside ordinary durable volumes; Sandcrab receives no repository operations. */
final class NativeRepositoryWorkspace {
    private final SandcrabClient client;
    private final AuthService auth;
    private final RepositorySnapshotExports exports;
    private final SelectedFileSaves saves;
    private final AccountCopyStore accounts;
    private final String profile;
    private final long bytes;
    private final long inodes;
    private final UUID application = UUID.randomUUID();

    NativeRepositoryWorkspace(
            SandcrabClient client,
            AuthService auth,
            RepositorySnapshotExports exports,
            SelectedFileSaves saves,
            AccountCopyStore accounts,
            String profile,
            long bytes,
            long inodes) {
        this.client = client;
        this.auth = auth;
        this.exports = exports;
        this.saves = saves;
        this.accounts = accounts;
        this.profile = SandcrabProtocol.profile(profile);
        this.bytes = bytes;
        this.inodes = inodes;
    }

    SandcrabProtocol.Info info() {
        SandcrabProtocol.Info info = client.info();
        if (!info.capabilities().containsAll(Set.of("sessions", "volumes", "files", "calls"))
                || !info.profiles().containsKey(profile)
                || !info.profiles().get(profile).hostCalls()) {
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
            exports.requireCurrentPublic(principal, workspace, record.publicExport());
        } else {
            auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY);
        }
    }

    NativeRepositoryLease open(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            Consumer<NativeRepositoryLease> admitted) {
        authorize(principal, held.owner(), held.record());
        SandcrabProtocol.Info info = info();
        if (held.record() == null) {
            return initialize(principal, held, requested, cancellation, info, admitted);
        }
        AccountCopyRecord retained = held.record();
        held.requireLive();
        if (retained.writer().storage() == null || retained.phase() == AccountCopyRecord.Phase.INITIALIZING) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED, true);
        }
        if (requested.isPresent() && !requested.get().equals(retained.state().baseCommit())) {
            throw new IllegalArgumentException("The requested commit differs from the acknowledged copy baseline");
        }
        fence(retained, info);
        AccountCopyRecord.NativeStorage storage = retained.writer().storage();
        storage.requireVolume(client.volume(storage.volumeId().toString()));
        var lease = lease(principal, held, storage, info, cancellation);
        admitted.accept(lease);
        held.bind(lease.writer());
        try {
            lease.open();
            lease.setup("cd /workspace/repository");
            return lease;
        } catch (RuntimeException failure) {
            lease.contain(failure);
            throw failure;
        }
    }

    private NativeRepositoryLease initialize(
            AuthPrincipal principal,
            AccountCommand held,
            Optional<String> requested,
            ExecutionCancellation cancellation,
            SandcrabProtocol.Info info,
            Consumer<NativeRepositoryLease> admitted) {
        var workspace = new WorkspaceId(held.owner().workspaceId());
        if (!held.owner().fullRead() && requested.isPresent()) {
            throw new IllegalArgumentException("Public execution starts from current publication; omit commit");
        }
        RepositorySnapshotExports.PublicExport projection =
                held.owner().fullRead() ? null : exports.createPublic(principal, workspace);
        RepositorySnapshotExports.Export export =
                projection == null ? exports.create(principal, workspace, requested) : projection.export();
        try {
            UUID copy = held.owner().newCopyId();
            var storage = new AccountCopyRecord.NativeStorage(copy, profile, bytes, inodes);
            var lease = lease(principal, held, storage, info, cancellation);
            admitted.accept(lease);
            held.initialize(
                    copy,
                    lease.writer(),
                    new SelectedFileSaves.State(export.commit()).snapshot(),
                    projection,
                    sink -> saves.visitOriginal(
                            principal, workspace, export.commit(), accounts.traversalLimits(), sink),
                    AccountCopyRecord.Phase.INITIALIZING);
            return initializeVolume(lease, held, principal, workspace, export);
        } finally {
            exports.release(export.exportId());
        }
    }

    private NativeRepositoryLease initializeVolume(
            NativeRepositoryLease lease,
            AccountCommand held,
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositorySnapshotExports.Export export) {
        try {
            lease.createVolume();
            lease.open();
            exports.withBundle(principal, workspace, export, path -> lease.uploadBundle(export, path));
            lease.setup("git clone --quiet --no-checkout --no-hardlinks /workspace/seed.bundle /workspace/repository"
                    + " && git -C /workspace/repository checkout --quiet --detach "
                    + ProtocolValues.hex(export.commit(), 40, "export commit")
                    + " && rm /workspace/seed.bundle && cd /workspace/repository");
            held.bind(lease.writer());
            return lease;
        } catch (RuntimeException failure) {
            lease.contain(failure);
            throw failure;
        }
    }

    private NativeRepositoryLease lease(
            AuthPrincipal principal,
            AccountCommand held,
            AccountCopyRecord.NativeStorage storage,
            SandcrabProtocol.Info info,
            ExecutionCancellation cancellation) {
        return new NativeRepositoryLease(
                client,
                storage,
                new AccountCopyRecord.Writer(
                        principal.subjectId(),
                        UUID.fromString(info.serverId()),
                        application,
                        UUID.randomUUID(),
                        storage),
                Math.min(120_000, info.profiles().get(profile).sessionTtlMillis()),
                () -> {
                    if (cancellation.isCancelled()) {
                        throw new WorkerUnavailableException();
                    }
                    authorize(principal, held.owner(), held.record());
                });
    }

    /** A changed daemon epoch establishes containment; a missing active record in the same epoch does not. */
    void fence(AccountCopyRecord record, SandcrabProtocol.Info info) {
        if (!record.writer().workerBootId().toString().equals(info.serverId())) {
            return;
        }
        var reference = new SandcrabProtocol.Reference(record.writer().leaseId().toString(), info.serverId());
        try {
            NativeRepositoryLease.close(client, reference, Duration.ofSeconds(15));
        } catch (SandcrabClient.Rejected missing) {
            if (!GameSandboxJob.missing(missing, "SESSION_NOT_FOUND")
                    || record.phase() != AccountCopyRecord.Phase.READY) {
                throw missing;
            }
        }
    }
}
