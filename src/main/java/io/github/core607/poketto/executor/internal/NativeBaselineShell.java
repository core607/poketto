package io.github.core607.poketto.executor.internal;

import java.time.Duration;
import java.util.UUID;

/** A sibling shell can serve a blocked host call. The user command keeps its own shell and deadline. */
final class NativeBaselineShell implements AutoCloseable {
    private static final Duration CLEANUP = Duration.ofSeconds(5);
    private final SandcrabClient client;
    private final NativeRepositoryLease lease;
    private final SandcrabProtocol.Reference reference;
    private SandcrabProtocol.Session session;
    private SandcrabProtocol.Reference outstanding;
    private boolean submitted;
    private boolean staging;

    NativeBaselineShell(SandcrabClient client, NativeRepositoryLease lease) {
        this.client = client;
        this.lease = lease;
        reference = new SandcrabProtocol.Reference(
                UUID.randomUUID().toString(), lease.plan().serverId());
    }

    void open() {
        lease.authorize();
        long deadline = System.nanoTime() + lease.remaining().toNanos();
        submitted = true;
        try {
            session = client.openChild(lease.session(), reference);
        } catch (SandcrabClient.Rejected rejected) {
            submitted = false;
            throw rejected;
        } catch (WorkerUnavailableException failure) {
            submitted = failure.mayHaveExecuted();
            throw failure;
        }
        while (!session.state().equals("ready")) {
            if (session.terminal() || session.state().equals("closing")) {
                throw new WorkerUnavailableException();
            }
            NativeRepositoryLease.pause(deadline, lease::authorize);
            session = client.session(reference);
        }
    }

    void prepareBundle() {
        if (execute(NativeGitBaseline.CLEAN, lease.remaining()) != 0) {
            throw new NativeGitBaseline.Pending("Cannot prepare the reserved Git transfer");
        }
        staging = true;
    }

    int execute(String source, Duration budget) {
        lease.authorize();
        SandcrabProtocol.CommandPlan command = lease.maintenanceCommand(source, budget);
        outstanding = command.reference();
        SandcrabProtocol.Run run;
        try {
            run = client.command(session, command);
        } catch (SandcrabClient.Rejected refused) {
            outstanding = null;
            throw refused;
        }
        long deadline = System.nanoTime()
                + Duration.ofMillis(command.timeoutMillis() + 1000).toNanos();
        while (!run.terminal()) {
            NativeRepositoryLease.pause(deadline, lease::authorize);
            run = client.get(command.reference());
        }
        client.forget(run);
        outstanding = null;
        Integer exit = run.result().exitCode();
        if (exit != null
                && run.result().code() == null
                && (run.state().equals("failed") || run.state().equals("succeeded"))) {
            return exit;
        }
        throw new WorkerUnavailableException();
    }

    @Override
    public void close() {
        if (!submitted) {
            return;
        }
        try {
            try {
                settleCommand();
                if (staging && execute(NativeGitBaseline.CLEAN, CLEANUP) != 0) {
                    throw new WorkerUnavailableException();
                }
            } finally {
                closeChild();
                settleCommand();
            }
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            // An unconfirmed helper or transfer must not be captured in a new account upper.
            lease.close();
            throw failure;
        }
    }

    /** A lost POST or forget reply can leave a receipt. Only this sibling's run may be cancelled. */
    private void settleCommand() {
        if (outstanding == null) {
            return;
        }
        try {
            SandcrabProtocol.Run run = client.get(outstanding);
            if (reference.id().equals(run.sessionId())) {
                if (!run.terminal()) {
                    run = client.cancel(outstanding);
                }
                long deadline = System.nanoTime() + CLEANUP.toNanos();
                while (!run.terminal()) {
                    NativeRepositoryLease.pause(deadline, () -> {});
                    run = client.get(outstanding);
                }
                client.forget(run);
            }
        } catch (SandcrabClient.Rejected missing) {
            if (!GameSandboxJob.missing(missing, "RUN_NOT_FOUND")) {
                throw missing;
            }
        }
        outstanding = null;
    }

    private void closeChild() {
        try {
            SandcrabProtocol.Session observed = client.session(reference);
            if (!lease.session().id().equals(observed.parentSessionId())) {
                throw new WorkerUnavailableException();
            }
            NativeRepositoryLease.close(client, reference, Duration.ofSeconds(15));
            client.forget(client.session(reference));
        } catch (SandcrabClient.Rejected missing) {
            if (!GameSandboxJob.missing(missing, "SESSION_NOT_FOUND")) {
                throw missing;
            }
        }
    }
}
