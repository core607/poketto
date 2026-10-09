package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameJson;
import io.github.core607.poketto.games.GameRunner;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Game admission is independent of repository sessions; every request uses a fresh finite job. */
final class IsolatedGameRunner implements GameRunner {
    private static final Logger log = LoggerFactory.getLogger(IsolatedGameRunner.class);
    private final WorkerClient worker;
    private final ObjectMapper json;
    private final Semaphore capacity;

    IsolatedGameRunner(WorkerClient worker, ObjectMapper json, int capacity) {
        this.worker = worker;
        this.json = json;
        this.capacity = new Semaphore(capacity);
    }

    @Override
    public Result run(Identity identity, GameBundle bundle, Request request) {
        var data = new WorkerRequests.Game(bundle, request);
        requireBounds(bundle, request, data);
        if (!capacity.tryAcquire()) {
            throw new GameException("GAME_BUSY", "The game worker is serving other players");
        }
        try {
            WorkerClient.Hello hello = worker.gameHello();
            var job = new WorkerClient.Identity(
                    identity.principalId(),
                    identity.accountId(),
                    identity.workspaceId().value(),
                    "0".repeat(64),
                    UUID.randomUUID());
            JsonNode response = worker.request(hello, job, "GAME", data, Duration.ofSeconds(10));
            var reply = WorkerResponses.read(response, Reply.class);
            if (!reply.ok()) {
                throw rejected(reply.code());
            }
            Result result = reply.result();
            GameJson.require(result.state(), 32 * 1024, json);
            checkJson(result.observation(), 32 * 1024);
            checkJson(result.presentation(), 32 * 1024);
            if (result.presentation() != null && result.presentation().image() != null) {
                if (!bundle.resources().containsKey(result.presentation().image())) {
                    throw new GameException("INVALID_GAME", "Presentation names an undeclared image");
                }
            }
            return result;
        } catch (WorkerUnavailableException failure) {
            throw new GameException("GAME_UNAVAILABLE", "The isolated game worker is unavailable", failure);
        } finally {
            capacity.release();
        }
    }

    private static GameException rejected(String code) {
        String mapped =
                switch (code == null ? "" : code) {
                    case "INVALID_GAME", "INVALID_GAME_OUTPUT", "GAME_FAILED", "GAME_LIMIT" -> "INVALID_GAME";
                    case "GAME_CAPACITY", "GAME_BUSY" -> "GAME_BUSY";
                    default -> "GAME_UNAVAILABLE";
                };
        return new GameException(mapped, "The isolated game step was refused or exceeded its limits");
    }

    private void requireBounds(GameBundle bundle, Request request, WorkerRequests.Game data) {
        checkJson(bundle, GameBundle.MAX_BYTES);
        if (request.state() != null) {
            GameJson.require(request.state(), 32 * 1024, json);
        }
        checkJson(data, 512 * 1024);
    }

    private void checkJson(Object value, int maximum) {
        if (json.writeValueAsBytes(value).length > maximum) {
            throw new GameException("GAME_LIMIT", "Game JSON exceeds its byte limit");
        }
    }

    @EventListener
    void revoked(AuthRevocation event) {
        try {
            WorkerClient.Hello hello = worker.gameHello();
            var zero = new UUID(0, 0);
            var identity =
                    new WorkerClient.Identity(zero, zero, event.workspaceId().value(), "0".repeat(64), zero);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (true) {
                JsonNode response = worker.request(
                        hello,
                        identity,
                        "REVOKE",
                        new WorkerRequests.Revoke(event.apiKeyIds(), event.accountIds()),
                        Duration.ofSeconds(3));
                var reply = WorkerResponses.read(response, Revoked.class);
                if (reply.ok() && "CLOSED".equals(reply.state())) {
                    return;
                }
                if (!reply.ok() || !"CLOSING".equals(reply.state()) || System.nanoTime() >= deadline) {
                    throw new WorkerUnavailableException();
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Game revocation wait interrupted", interrupted);
        } catch (WorkerUnavailableException unavailable) {
            log.warn(
                    "Game termination after revocation is unconfirmed; jobs retain their finite lifetime", unavailable);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Revoked(boolean ok, String state) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Handshake(
            boolean ok,
            int version,
            int gameProtocol,
            int codeActProtocol,
            int maxFrameBytes,
            UUID workerBootId,
            int leaseSeconds,
            int renewAfterSeconds) {
        Handshake {
            ProtocolValues.require(
                    ok && version == 1 && gameProtocol == 1 && codeActProtocol == 0,
                    "game handshake",
                    "must name the isolated game worker");
            ProtocolValues.require(
                    workerBootId != null && maxFrameBytes == WorkerClient.MAX_FRAME,
                    "game handshake",
                    "must provide bounded protocol identity");
            ProtocolValues.inRange(leaseSeconds, 6, 30, "game lease seconds");
            ProtocolValues.inRange(renewAfterSeconds, 1, 5, "game renew interval");
        }
    }

    record Reply(boolean ok, UUID requestId, String code, Result result) {
        Reply {
            if (ok && result == null) {
                throw new IllegalArgumentException("A successful game reply requires a result");
            }
        }
    }
}
