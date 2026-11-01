package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One command's sandbox admission; files belong to the volume, and uncertainty never removes them. */
final class NativeRepositoryLease {
    private final SandcrabClient client;
    private final AccountCopyRecord.NativeStorage storage;
    private final AccountCopyRecord.Writer writer;
    private final SandcrabProtocol.SessionPlan plan;
    private final Runnable authorize;
    private final int outputLimit;
    private final long transferLimit;
    private final List<SandcrabProtocol.Reference> commands = new ArrayList<>();
    private final List<SandcrabProtocol.Reference> uploads = new ArrayList<>();
    private SandcrabProtocol.Session session;
    private volatile boolean opening;
    private volatile boolean submitted;
    private volatile boolean contained;
    private volatile boolean stopped;
    private boolean volumeMayExist;
    private volatile Long commandDeadline;

    NativeRepositoryLease(
            SandcrabClient client,
            AccountCopyRecord.NativeStorage storage,
            AccountCopyRecord.Writer writer,
            long ttlMillis,
            int outputLimit,
            long transferLimit,
            Runnable authorize) {
        this.client = client;
        this.storage = storage;
        this.writer = writer;
        this.authorize = authorize;
        this.outputLimit = ProtocolValues.inRange(outputLimit, 1, 1_048_576, "repository output limit");
        this.transferLimit = ProtocolValues.inRange(transferLimit, 1024, 1_073_741_824, "repository transfer limit");
        plan = new SandcrabProtocol.SessionPlan(
                writer.leaseId().toString(),
                writer.workerBootId().toString(),
                storage.profile(),
                ttlMillis,
                storage.volumeId().toString());
    }

    AccountCopyRecord.Writer writer() {
        return writer;
    }

    SandcrabProtocol.Session session() {
        return session;
    }

    SandcrabProtocol.SessionPlan plan() {
        return plan;
    }

    boolean mayHaveExecuted() {
        return submitted;
    }

    /** Borrows only the runtime; this lease's authorization closure belongs to the current command. */
    boolean resume(SandcrabProtocol.Session expected) {
        authorize();
        submitted = true;
        if (!plan.reference().equals(expected.reference())) {
            throw new WorkerUnavailableException();
        }
        try {
            session = client.renew(expected, plan.ttlMillis());
        } catch (SandcrabClient.Rejected refused) {
            if (GameSandboxJob.missing(refused, "SESSION_NOT_FOUND")) {
                return false;
            }
            if (refused.code().equals("SESSION_NOT_READY") && inspect().terminal()) {
                return false;
            }
            throw refused;
        }
        authorize();
        if (!session.state().equals("ready")) {
            if (session.terminal()) {
                return false;
            }
            throw new WorkerUnavailableException();
        }
        return true;
    }

    SandcrabProtocol.Session inspect() {
        SandcrabProtocol.Session current = client.session(plan.reference());
        if (!current.profile().equals(plan.profile())
                || !plan.volumeId().equals(current.volumeId())
                || current.parentSessionId() != null) {
            throw new WorkerUnavailableException();
        }
        return current;
    }

    void authorize() {
        if (stopped) {
            throw new WorkerUnavailableException();
        }
        authorize.run();
    }

    void createVolume() {
        createVolume(Duration.ofSeconds(60));
    }

    void createVolume(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        var requested = new SandcrabVolume.Plan(
                storage.volumeId().toString(),
                plan.serverId(),
                storage.profile(),
                null,
                storage.byteLimit(),
                storage.inodeLimit());
        SandcrabVolume volume = admitVolume(requested, deadline);
        while (!volume.state().equals("ready")) {
            if (!volume.state().equals("creating")) {
                throw new WorkerUnavailableException();
            }
            pause(deadline, authorize);
            volume = client.volume(volume, volumeRemaining(deadline));
        }
        storage.requireVolume(volume);
    }

    private SandcrabVolume admitVolume(SandcrabVolume.Plan requested, long deadline) {
        while (true) {
            authorize();
            Duration remaining = volumeRemaining(deadline);
            volumeMayExist = true;
            try {
                return client.createVolume(requested, remaining);
            } catch (SandcrabClient.Rejected refused) {
                if (refused.status() != 429 || !refused.code().equals("STORAGE_CAPACITY")) {
                    throw refused;
                }
                // This refusal precedes native record creation. No earlier uncertain request is retried.
                volumeMayExist = false;
                pause(deadline, authorize);
            }
        }
    }

    /** False only before dispatch or after this creation request's definite storage-capacity refusal. */
    boolean volumeMayExist() {
        return volumeMayExist;
    }

    private static Duration volumeRemaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new WorkerUnavailableException();
        }
        return Duration.ofNanos(remaining);
    }

    void open() {
        authorize();
        synchronized (this) {
            if (stopped) {
                throw new WorkerUnavailableException();
            }
            opening = true;
            submitted = true;
        }
        try {
            session = client.open(plan);
        } catch (WorkerUnavailableException failure) {
            submitted = failure.mayHaveExecuted();
            throw failure;
        } catch (SandcrabClient.Rejected failure) {
            submitted = failure.code().equals("ID_CONFLICT");
            throw failure;
        } finally {
            opening = false;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!session.state().equals("ready")) {
            if (session.terminal() || session.state().equals("closing")) {
                throw new WorkerUnavailableException();
            }
            pause(deadline, authorize);
            session = client.session(plan.reference());
        }
    }

    void uploadBundle(RepositorySnapshotExports.Export export, Path source) {
        authorize();
        var transfer = new SandcrabProtocol.UploadPlan(
                UUID.randomUUID().toString(),
                plan.serverId(),
                plan.id(),
                NativeRepositoryLayout.SEED,
                "raw",
                export.bundleBytes(),
                export.bundleSha256(),
                false,
                true,
                0600);
        uploads.add(transfer.reference());
        client.register(session, transfer);
        if (!client.send(transfer, source).succeeded()) {
            throw new WorkerUnavailableException();
        }
        authorize();
    }

    /** App-owned initialization uses the public command API; no Git semantics enter the daemon. */
    void setup(String source) {
        SandcrabProtocol.Run run = command(UUID.randomUUID(), source, Duration.ofSeconds(30));
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!run.terminal()) {
            pause(deadline, authorize);
            run = client.get(run.reference());
        }
        if (!run.state().equals("succeeded") || !run.result().success()) {
            throw new WorkerUnavailableException();
        }
    }

    SandcrabProtocol.Run command(UUID id, String source, Duration timeout) {
        authorize();
        var command = new SandcrabProtocol.CommandPlan(
                id.toString(), plan.serverId(), source, timeout.toMillis(), outputLimit);
        commands.add(command.reference());
        commandDeadline = System.nanoTime() + timeout.toNanos();
        try {
            return client.command(session, command);
        } catch (SandcrabClient.Rejected refused) {
            if (!refused.code().equals("ID_CONFLICT")) {
                commands.remove(command.reference());
            }
            throw refused;
        }
    }

    boolean outOfTime(Duration margin) {
        if (margin.isNegative()) {
            throw new IllegalArgumentException("Command time margin must not be negative");
        }
        Long deadline = commandDeadline;
        if (deadline == null) {
            throw new IllegalStateException("No command has established a deadline");
        }
        return deadline - System.nanoTime() <= margin.toNanos();
    }

    Duration remaining() {
        Long deadline = commandDeadline;
        if (deadline == null) {
            throw new IllegalStateException("No command has established a deadline");
        }
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new WorkerUnavailableException(new IllegalStateException("Repository command deadline elapsed"));
        }
        return Duration.ofNanos(nanos);
    }

    long transferLimit() {
        return transferLimit;
    }

    void close() {
        stopped = true;
        if (opening) {
            throw new WorkerUnavailableException();
        }
        if (!submitted) {
            contained = true;
            return;
        }
        if (!contained) {
            close(client, plan.reference(), Duration.ofSeconds(15));
            contained = true;
        }
    }

    boolean contained() {
        return contained;
    }

    /** Forgetting follows the durable account acknowledgement and confirmed containment. */
    void releaseReceipts(boolean retainSession) {
        if (!retainSession && !contained) {
            throw new WorkerUnavailableException();
        }
        for (SandcrabProtocol.Reference command : commands) {
            SandcrabProtocol.Run result = client.get(command);
            if (!result.terminal()) {
                throw new WorkerUnavailableException();
            }
            client.forget(result);
        }
        for (SandcrabProtocol.Reference upload : uploads) {
            SandcrabProtocol.Upload result = client.upload(upload);
            if (!result.forgettable()) {
                result = client.cancelUpload(upload);
            }
            client.forget(result);
        }
        if (submitted && !retainSession) {
            client.forget(client.session(plan.reference()));
        }
    }

    SandcrabProtocol.Upload upload(String path, NativeTransferStore.File file) {
        authorize();
        remaining();
        if (file.size() > transferLimit) {
            throw new NativeTransferStore.Capacity();
        }
        var transfer = new SandcrabProtocol.UploadPlan(
                UUID.randomUUID().toString(),
                plan.serverId(),
                plan.id(),
                path,
                "raw",
                file.size(),
                file.sha256(),
                false,
                true,
                0600);
        uploads.add(transfer.reference());
        client.register(session, transfer, remaining());
        authorize();
        SandcrabProtocol.Upload result = client.send(transfer, file.path(), remaining(), () -> {
            authorize();
            remaining();
        });
        authorize();
        remaining();
        return result;
    }

    static void close(SandcrabClient client, SandcrabProtocol.Reference reference, Duration timeout) {
        SandcrabProtocol.Session session = client.session(reference);
        if (!session.terminal()) {
            session = client.close(reference);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!session.terminal()) {
            pause(deadline, () -> {});
            session = client.session(reference);
        }
    }

    static void pause(long deadline, Runnable authorize) {
        authorize.run();
        if (System.nanoTime() >= deadline) {
            throw new WorkerUnavailableException();
        }
        try {
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        }
    }

    static final class Ended extends IllegalStateException {
        Ended() {
            super("The retained native session ended before this command was submitted");
        }
    }
}
