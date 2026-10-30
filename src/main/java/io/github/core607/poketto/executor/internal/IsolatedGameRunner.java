package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameRunner;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.ObjectMapper;

/** Poketto owns game authority and interpretation; Sandcrab owns ephemeral execution and containment. */
final class IsolatedGameRunner implements GameRunner, AutoCloseable {
    private final GamePayload payload;
    private final GameSandboxPool pool;
    private final Consumer<Identity> authorize;

    IsolatedGameRunner(
            SandcrabClient client, ObjectMapper json, int capacity, Path ownerFile, Consumer<Identity> authorize) {
        payload = new GamePayload(json);
        pool = new GameSandboxPool(client, capacity, ownerFile);
        this.authorize = authorize;
    }

    @Override
    public Result run(Identity identity, GameBundle bundle, Request request) {
        byte[] input = payload.archive(bundle, request);
        GameSandboxJob job;
        try {
            job = pool.begin(identity);
        } catch (WorkerUnavailableException | IllegalArgumentException failure) {
            throw unavailable(failure);
        }
        try {
            // Registration precedes the fresh authority check, so a concurrent revocation can find this job.
            authorize.accept(identity);
            String output = job.execute(input);
            return payload.result(bundle, output);
        } catch (SandcrabClient.Rejected refused) {
            String code = refused.status() == 429 ? "GAME_BUSY" : "GAME_UNAVAILABLE";
            throw new GameException(code, "The isolated game service refused this step", refused);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(interrupted);
        } catch (WorkerUnavailableException failure) {
            throw unavailable(failure);
        } finally {
            pool.finish(job);
        }
    }

    private static GameException unavailable(Exception cause) {
        return new GameException("GAME_UNAVAILABLE", "The isolated game service is unavailable", cause);
    }

    @EventListener
    void revoked(AuthRevocation event) {
        pool.revoked(event);
    }

    void collect() {
        pool.collect();
    }

    void collectSafely() {
        pool.collectSafely();
    }

    @Override
    public void close() {
        pool.close();
    }
}
