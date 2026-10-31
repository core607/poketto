package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds admission until containment; one shared file lock fences application owners of the game service. */
final class GameSandboxPool implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GameSandboxPool.class);
    private final SandcrabClient client;
    private final Semaphore capacity;
    private final RetainedFileLocks.Held ownership;
    private final ConcurrentHashMap<String, GameSandboxJob> jobs = new ConcurrentHashMap<>();
    private final Set<String> owned = ConcurrentHashMap.newKeySet();
    private final GameSandboxOrphans orphans;
    private final Object collection = new Object();
    private boolean closed;

    GameSandboxPool(SandcrabClient client, int maximum, Path ownerFile) {
        ProtocolValues.inRange(maximum, 1, 16, "game concurrency");
        this.client = client;
        capacity = new Semaphore(maximum);
        ownership = own(ownerFile);
        orphans = new GameSandboxOrphans(client, owned::contains);
        verifyService();
    }

    private void verifyService() {
        try {
            SandcrabProtocol.Info info = info();
            client.sessions(info.serverId(), null);
            client.runs(info.serverId(), null);
            client.uploads(null);
        } catch (RuntimeException unavailable) {
            log.warn("Native game service is unavailable or incompatible", unavailable);
            try {
                ownership.close();
            } catch (IOException release) {
                unavailable.addSuppressed(release);
            }
            throw new IllegalStateException(
                    "Enabled games require a ready dedicated native Sandcrab service", unavailable);
        }
    }

    private static RetainedFileLocks.Held own(Path file) {
        try {
            Path path = file.toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            Path canonical = path.getParent().toRealPath().resolve(path.getFileName());
            return RetainedFileLocks.acquire(
                    canonical,
                    () -> FileChannel.open(
                            canonical, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RetainedCopyException failure) {
            throw new IllegalStateException("One application must exclusively own the game service lock", failure);
        }
    }

    GameSandboxJob begin(GameRunner.Identity identity) {
        Objects.requireNonNull(identity, "Game execution owner is required");
        SandcrabProtocol.Info info = info();
        synchronized (this) {
            ownership.requireValid();
            if (closed) {
                throw new GameException("GAME_UNAVAILABLE", "The game service is shutting down");
            }
            if (!capacity.tryAcquire()) {
                throw new GameException("GAME_BUSY", "The game service is serving other players or reclaiming a job");
            }
            var job = new GameSandboxJob(client, identity, info);
            // All identities become protected before any request can reach the daemon.
            owned.add(job.plan.id());
            owned.add(job.upload.id());
            owned.add(job.command.id());
            jobs.put(job.plan.id(), job);
            return job;
        }
    }

    private SandcrabProtocol.Info info() {
        SandcrabProtocol.Info info = client.info();
        ProtocolValues.require(
                info.profiles().keySet().equals(Set.of("games")), "game service", "must have only the games profile");
        ProtocolValues.require(
                !info.capabilities().contains("volumes"), "game service", "must not own durable volumes");
        SandcrabProtocol.Profile profile = info.profiles().get("games");
        ProtocolValues.require(!profile.hostCalls(), "game profile", "must disable host calls");
        ProtocolValues.require(
                profile.timeoutMillis() >= 5000 && profile.sessionTtlMillis() >= 15_000,
                "game profile",
                "must permit the bounded game lifetime");
        ProtocolValues.require(
                profile.outputLimitBytes() >= GamePayload.OUTPUT_BYTES
                        && profile.transferBytes() >= GamePayload.UPLOAD_BYTES,
                "game profile",
                "must permit bounded game input and output");
        return info;
    }

    void finish(GameSandboxJob job) {
        job.active = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            do {
                if (reclaim(job, job.submitted ? info().serverId() : job.plan.serverId())) {
                    return;
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Game cleanup wait interrupted; admission remains held", interrupted);
        } catch (WorkerUnavailableException | SandcrabClient.Rejected | IllegalArgumentException failure) {
            log.warn("Game cleanup is unconfirmed; its owner and admission remain retained", failure);
        }
    }

    private boolean reclaim(GameSandboxJob job, String epoch) {
        if (!job.reclaim(epoch)) {
            return false;
        }
        if (jobs.remove(job.plan.id(), job)) {
            owned.remove(job.plan.id());
            owned.remove(job.upload.id());
            owned.remove(job.command.id());
            capacity.release();
        }
        return true;
    }

    /** Only this method walks orphan pages, so their cursors have one owner. */
    void collect() {
        synchronized (collection) {
            ownership.requireValid();
            SandcrabProtocol.Info info = info();
            for (GameSandboxJob job : jobs.values()) {
                reclaim(job, info.serverId());
            }
            orphans.collect(info.serverId());
        }
    }

    void revoked(AuthRevocation event) {
        for (GameSandboxJob job : jobs.values()) {
            if (matches(job.owner, event)) {
                job.cancelled = true;
            }
        }
        collectSafely();
    }

    private static boolean matches(GameRunner.Identity owner, AuthRevocation event) {
        if (!owner.workspaceId().equals(event.workspaceId())) {
            return false;
        }
        return switch (owner) {
            case GameRunner.Player player ->
                event.accountIds().contains(player.principal().accountId())
                        || event.apiKeyIds().contains(player.principal().subjectId());
            case GameRunner.Validation ignored -> true;
        };
    }

    void collectSafely() {
        try {
            collect();
        } catch (RuntimeException failure) {
            log.warn("Game task reconciliation could not complete this batch", failure);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            jobs.values().forEach(job -> job.cancelled = true);
        }
        synchronized (collection) {
            stopAndRelease();
        }
    }

    private void stopAndRelease() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!jobs.isEmpty() && System.nanoTime() < deadline) {
            collectSafely();
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping game execution", interrupted);
            }
        }
        if (!jobs.isEmpty()) {
            // Releasing ownership while a caller can still dispatch would race the next application.
            throw new IllegalStateException(
                    "Game callers or containment remain unconfirmed; ownership stays held until process exit");
        }
        try {
            ownership.close();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not release game-service ownership", failure);
        }
    }
}
