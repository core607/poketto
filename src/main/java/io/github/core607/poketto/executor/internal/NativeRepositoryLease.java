package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.executor.internal.NativeRepositoryPhase.Stage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final Duration setupTimeout;
    private final List<SandcrabProtocol.Reference> commands = new ArrayList<>();
    private final List<SandcrabProtocol.Reference> uploads = new ArrayList<>();
    private SandcrabProtocol.Session session;
    private SandcrabWorkspaces.Handle workspace;
    private volatile SandcrabProtocol.Reference parent;
    private SandcrabProtocol.Reference activeRun;
    private volatile boolean opening;
    private volatile boolean submitted;
    private volatile boolean contained;
    private volatile boolean stopped;
    private volatile Long commandDeadline;

    NativeRepositoryLease(
            SandcrabClient client,
            AccountCopyRecord.NativeStorage storage,
            AccountCopyRecord.Writer writer,
            long ttlMillis,
            int outputLimit,
            long transferLimit,
            long profileTimeout,
            Runnable authorize) {
        this.client = client;
        this.storage = storage;
        this.writer = writer;
        this.authorize = authorize;
        this.outputLimit = ProtocolValues.inRange(outputLimit, 1, 1_048_576, "repository output limit");
        setupTimeout = Duration.ofMillis(
                Math.min(30_000, ProtocolValues.inRange(profileTimeout, 1, 300_000, "profile timeout")));
        this.transferLimit = ProtocolValues.inRange(transferLimit, 1024, 1_073_741_824, "repository transfer limit");
        plan = new SandcrabProtocol.SessionPlan(
                writer.leaseId().toString(),
                writer.workerBootId().toString(),
                storage.profile(),
                ttlMillis,
                storage.volumeId() == null ? null : storage.volumeId().toString(),
                storage.templateId());
    }

    AccountCopyRecord.Writer writer() {
        return writer;
    }

    SandcrabProtocol.Session session() {
        if (session == null && parent != null) {
            var resolved = client.session(parent);
            if (!storage.profile().equals(resolved.profile())
                    || !Objects.equals(storage.templateId(), resolved.templateId())
                    || resolved.parentSessionId() != null
                    || !Objects.equals(workspace.id(), resolved.volumeId())) {
                throw new WorkerUnavailableException();
            }
            session = resolved;
        }
        return session;
    }

    void workspace(SandcrabWorkspaces.Handle value) {
        workspace = value;
    }

    SandcrabWorkspaces.Handle workspace() {
        return Objects.requireNonNull(workspace);
    }

    SandcrabProtocol.SessionPlan plan() {
        return plan;
    }

    boolean mayHaveExecuted() {
        return submitted;
    }

    /** Borrows only the runtime; this lease's authorization closure belongs to the current command. */
    boolean resume(SandcrabProtocol.Session expected) {
        try (var phase = NativeRepositoryPhase.start(Stage.SESSION_RENEW)) {
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
    }

    SandcrabProtocol.Session inspect() {
        SandcrabProtocol.Session current = client.session(plan.reference());
        if (!current.profile().equals(plan.profile())
                || !Objects.equals(plan.volumeId(), current.volumeId())
                || !Objects.equals(plan.templateId(), current.templateId())
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

    void open() {
        try (var phase = NativeRepositoryPhase.start(Stage.SESSION_START)) {
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
    }

    /** App-owned initialization uses the public command API; no Git semantics enter the daemon. */
    void setup(String source) {
        try (var phase = NativeRepositoryPhase.start(Stage.SETUP_COMMAND)) {
            SandcrabProtocol.Run run = submit(UUID.randomUUID(), source, setupTimeout, null);
            long deadline = System.nanoTime() + setupTimeout.plusSeconds(5).toNanos();
            while (!run.terminal()) {
                pause(deadline, authorize);
                run = client.get(run.reference());
            }
            if (!run.state().equals("succeeded") || !run.result().success()) {
                throw new WorkerUnavailableException();
            }
        }
    }

    SandcrabProtocol.Run command(UUID id, String source, Duration timeout) {
        return submit(
                id,
                source,
                timeout,
                workspace == null ? null : NativeRepositoryLayout.attach() + " && " + NativeRepositoryCli.install());
    }

    private synchronized SandcrabProtocol.Run submit(UUID id, String source, Duration timeout, String initialization) {
        authorize();
        var command = new SandcrabProtocol.CommandPlan(
                id.toString(), plan.serverId(), source, timeout.toMillis(), outputLimit);
        commands.add(command.reference());
        commandDeadline = System.nanoTime() + timeout.toNanos();
        try {
            if (workspace == null) {
                return client.command(session, command);
            }
            activeRun = command.reference();
            SandcrabProtocol.Run run = client.workspaces().submit(workspace, command, initialization);
            parent = new SandcrabProtocol.Reference(run.sessionId(), workspace.serverId());
            session = null;
            return run;
        } catch (SandcrabClient.Rejected refused) {
            if (!refused.code().equals("ID_CONFLICT")) {
                activeRun = null;
                commands.remove(command.reference());
            }
            throw refused;
        }
    }

    synchronized boolean cancelWorkspace() {
        stopped = true;
        if (activeRun != null) {
            SandcrabProtocol.Run run;
            try {
                run = client.get(activeRun);
            } catch (SandcrabClient.Rejected missing) {
                if (!GameSandboxJob.missing(missing, "RUN_NOT_FOUND")) {
                    throw missing;
                }
                // A delayed request can still arrive. Retiring its generation fences it before returning capacity.
                try {
                    client.workspaces().remove(workspace, () -> {});
                } catch (SandcrabClient.Rejected gone) {
                    if (!GameSandboxJob.missing(gone, "WORKSPACE_NOT_FOUND")
                            && !(gone.status() == 409 && gone.code().equals("WORKSPACE_CHANGED"))) {
                        throw gone;
                    }
                }
                return true;
            }
            if (!Objects.equals(run.workspaceGeneration(), workspace.generation())) {
                throw new WorkerUnavailableException();
            }
            if (!run.terminal()) {
                run = client.cancel(activeRun);
            }
            return run.terminal();
        }
        return true;
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

    int runMaintenance(String source) {
        try (var shell = new NativeMaintenanceShell(client, this)) {
            shell.open();
            return shell.execute(source, remaining());
        }
    }

    SandcrabProtocol.CommandPlan maintenanceCommand(String source, Duration budget) {
        long millis = Math.max(1, Math.min(setupTimeout.toMillis(), budget.toMillis()));
        return new SandcrabProtocol.CommandPlan(
                UUID.randomUUID().toString(), plan.serverId(), source, millis, outputLimit);
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
    void releaseReceipts(boolean retainSession, SandcrabProtocol.Run completed) {
        if (!retainSession && !contained) {
            throw new WorkerUnavailableException();
        }
        for (SandcrabProtocol.Reference command : commands) {
            SandcrabProtocol.Run result =
                    completed != null && command.equals(completed.reference()) ? completed : client.get(command);
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
                session().id(),
                path,
                "raw",
                file.size(),
                file.sha256(),
                false,
                0600);
        uploads.add(transfer.reference());
        client.register(session(), transfer, remaining());
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
        try (var phase = NativeRepositoryPhase.start(Stage.SESSION_CONTAINMENT)) {
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
