package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import java.time.Duration;
import java.util.UUID;

/** Every mutation has one pre-registered identity; losing a reply never starts a replacement job. */
final class GameSandboxJob {
    private final String source;
    final GameRunner.Identity owner;
    final SandcrabProtocol.SessionPlan plan;
    final SandcrabProtocol.Reference upload;
    final SandcrabProtocol.Reference command;
    volatile boolean active = true;
    volatile boolean cancelled;
    volatile boolean submitted;
    boolean contained;
    private final SandcrabClient client;

    GameSandboxJob(SandcrabClient client, GameRunner.Identity owner, SandcrabProtocol.Info info, String source) {
        this.client = client;
        this.owner = owner;
        this.source = source;
        plan = new SandcrabProtocol.SessionPlan(
                id(),
                info.serverId(),
                "games",
                Math.min(15_000, info.profiles().get("games").sessionTtlMillis()),
                null);
        upload = new SandcrabProtocol.Reference(id(), info.serverId());
        command = new SandcrabProtocol.Reference(id(), info.serverId());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    String execute(byte[] input) throws InterruptedException {
        checkCancelled();
        submitted = true;
        SandcrabProtocol.Session session;
        try {
            session = client.open(plan);
        } catch (SandcrabClient.Rejected refused) {
            submitted = refused.code().equals("ID_CONFLICT");
            throw refused;
        } catch (WorkerUnavailableException unavailable) {
            submitted = unavailable.mayHaveExecuted();
            throw unavailable;
        }
        session = ready(session);
        checkCancelled();
        var transfer = new SandcrabProtocol.UploadPlan(
                upload.id(),
                upload.serverId(),
                plan.id(),
                "input.json",
                "raw",
                input.length,
                SandcrabProtocol.sha256(input),
                false,
                true,
                0600);
        client.register(session, transfer);
        if (!client.send(transfer, input).succeeded()) {
            throw new WorkerUnavailableException();
        }
        checkCancelled();
        var step = new SandcrabProtocol.CommandPlan(
                command.id(), command.serverId(), source, 5000, GamePayload.OUTPUT_BYTES);
        SandcrabProtocol.Run result = client.command(session, step);
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!result.terminal()) {
            waitFor(deadline);
            result = client.get(command);
        }
        checkCancelled();
        if (!result.state().equals("succeeded") || !result.result().success()) {
            throw new GameException("INVALID_GAME", "The isolated game step failed or exceeded its limits");
        }
        return result.result().stdout();
    }

    private SandcrabProtocol.Session ready(SandcrabProtocol.Session session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!session.state().equals("ready")) {
            if (session.terminal()) {
                throw new WorkerUnavailableException();
            }
            waitFor(deadline);
            session = client.session(plan.reference());
        }
        return session;
    }

    private void waitFor(long deadline) throws InterruptedException {
        checkCancelled();
        if (System.nanoTime() >= deadline) {
            throw new WorkerUnavailableException();
        }
        Thread.sleep(25);
    }

    void checkCancelled() {
        if (cancelled) {
            throw new GameException("GAME_UNAVAILABLE", "The game execution was cancelled");
        }
    }

    /** A missing record before confirmed containment may still be a delayed admission. */
    synchronized boolean reclaim(String epoch) {
        if (!plan.serverId().equals(epoch)) {
            cancelled = true;
            return !active;
        }
        if (active && !cancelled) {
            return false;
        }
        if (!submitted) {
            return !active;
        }
        SandcrabProtocol.Session session = null;
        try {
            session = client.session(plan.reference());
            if (!session.terminal()) {
                session = client.close(plan.reference());
            }
            contained = session.terminal();
        } catch (SandcrabClient.Rejected absent) {
            if (!missing(absent, "SESSION_NOT_FOUND")) {
                throw absent;
            }
        }
        if (!contained || active) {
            return false;
        }
        if (!releaseUpload() || !releaseCommand()) {
            return false;
        }
        if (session != null) {
            client.forget(session);
        }
        return true;
    }

    private boolean releaseUpload() {
        try {
            SandcrabProtocol.Upload record = client.upload(upload);
            ProtocolValues.require(
                    plan.id().equals(record.sessionId()), "game upload", "must belong to its root session");
            if (!record.forgettable()) {
                record = client.cancelUpload(upload);
            }
            if (!record.forgettable()) {
                return false;
            }
            client.forget(record);
            return true;
        } catch (SandcrabClient.Rejected absent) {
            if (!missing(absent, "UPLOAD_NOT_FOUND")) {
                throw absent;
            }
            return true;
        }
    }

    private boolean releaseCommand() {
        try {
            SandcrabProtocol.Run record = client.get(command);
            ProtocolValues.require(
                    plan.id().equals(record.sessionId()), "game command", "must belong to its root session");
            if (!record.terminal()) {
                return false;
            }
            client.forget(record);
            return true;
        } catch (SandcrabClient.Rejected absent) {
            if (!missing(absent, "RUN_NOT_FOUND")) {
                throw absent;
            }
            return true;
        }
    }

    static boolean missing(SandcrabClient.Rejected failure, String code) {
        return failure.status() == 404 && failure.code().equals(code);
    }
}
