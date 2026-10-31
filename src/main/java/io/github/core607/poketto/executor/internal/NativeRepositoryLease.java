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
    private final List<SandcrabProtocol.Reference> commands = new ArrayList<>();
    private SandcrabProtocol.Reference upload;
    private SandcrabProtocol.Session session;
    private volatile boolean opening;
    private volatile boolean submitted;
    private volatile boolean contained;
    private volatile boolean stopped;

    NativeRepositoryLease(
            SandcrabClient client,
            AccountCopyRecord.NativeStorage storage,
            AccountCopyRecord.Writer writer,
            long ttlMillis,
            Runnable authorize) {
        this.client = client;
        this.storage = storage;
        this.writer = writer;
        this.authorize = authorize;
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

    void authorize() {
        if (stopped) {
            throw new WorkerUnavailableException();
        }
        authorize.run();
    }

    void createVolume() {
        authorize();
        SandcrabVolume volume = client.createVolume(new SandcrabVolume.Plan(
                storage.volumeId().toString(),
                plan.serverId(),
                storage.profile(),
                null,
                storage.byteLimit(),
                storage.inodeLimit()));
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!volume.state().equals("ready")) {
            if (!volume.state().equals("creating")) {
                throw new WorkerUnavailableException();
            }
            pause(deadline, authorize);
            volume = client.volume(volume);
        }
        storage.requireVolume(volume);
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
                "seed.bundle",
                "raw",
                export.bundleBytes(),
                export.bundleSha256(),
                false,
                true,
                0600);
        upload = transfer.reference();
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
        var command =
                new SandcrabProtocol.CommandPlan(id.toString(), plan.serverId(), source, timeout.toMillis(), 65536);
        commands.add(command.reference());
        return client.command(session, command);
    }

    void contain(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException uncontained) {
            failure.addSuppressed(uncontained);
        }
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
    void releaseReceipts() {
        if (!contained) {
            throw new WorkerUnavailableException();
        }
        for (SandcrabProtocol.Reference command : commands) {
            SandcrabProtocol.Run result = client.get(command);
            if (!result.terminal()) {
                throw new WorkerUnavailableException();
            }
            client.forget(result);
        }
        if (upload != null) {
            SandcrabProtocol.Upload result = client.upload(upload);
            if (!result.forgettable()) {
                result = client.cancelUpload(upload);
            }
            client.forget(result);
        }
        if (submitted) {
            client.forget(client.session(plan.reference()));
        }
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
}
