package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameJson;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Account operations require the caller's guard; token-bound failed-attempt cleanup changes no saved state. */
final class GameSaveStore {
    private static final String COLUMNS =
            "save_id,account_id,creation_request,workspace_id,article_id,package_version,state,revision,seed,last_digest,last_result,pending_digest,pending_until,updated_at,pending_id,creation_digest";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    GameSaveStore(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    long nextRequest(UUID account) {
        List<Long> values =
                jdbc.queryForList("select next_request from game_accounts where account_id=?", Long.class, account);
        return values.isEmpty() ? 1 : values.getFirst();
    }

    List<Save> list(UUID account) {
        return jdbc.query(
                "select " + COLUMNS + " from game_saves where account_id=? order by updated_at desc limit 20",
                this::row,
                account);
    }

    Optional<Save> creation(UUID account, long request) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from game_saves where account_id=? and creation_request=?",
                        this::row,
                        account,
                        request)
                .stream()
                .findFirst();
    }

    Save get(UUID account, UUID save) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from game_saves where account_id=? and save_id=?",
                        this::row,
                        account,
                        save)
                .stream()
                .findFirst()
                .orElseThrow(() -> new GameException("SAVE_UNAVAILABLE", "This account has no such game save"));
    }

    Save create(UUID account, long request, GameLibrary.Published game, long seed, String digest) {
        long next = nextRequest(account);
        if (request < next) {
            throw new GameException("SAVE_REMOVED", "This creation request was already used and its save was removed");
        }
        if (request != next || next == Long.MAX_VALUE) {
            throw conflict();
        }
        if (list(account).size() >= 20) {
            throw new GameException(
                    "SAVE_CAPACITY", "Remove a saved game before starting another; at most 20 are retained");
        }
        var id = UUID.randomUUID();
        jdbc.update(
                "insert into game_saves(save_id,account_id,creation_request,workspace_id,article_id,package_version,seed,pending_digest,pending_until,pending_id,creation_digest) values (?,?,?,?,?,?,?,?,?,?,?)",
                id,
                account,
                request,
                game.workspaceId().value(),
                game.articleId(),
                game.version(),
                seed,
                digest,
                Timestamp.from(clock.instant().plusSeconds(30)),
                UUID.randomUUID(),
                digest);
        jdbc.update(
                "insert into game_accounts(account_id,next_request) values (?,?) on conflict(account_id) do update set next_request=excluded.next_request",
                account,
                next + 1);
        return get(account, id);
    }

    Save reserve(Save before, long expected, String digest) {
        requireIdle(before);
        if (before.revision() != expected || expected == Long.MAX_VALUE) {
            throw conflict();
        }
        jdbc.update(
                "update game_saves set pending_digest=?,pending_until=?,pending_id=? where account_id=? and save_id=?",
                digest,
                Timestamp.from(clock.instant().plusSeconds(30)),
                UUID.randomUUID(),
                before.account(),
                before.id());
        return get(before.account(), before.id());
    }

    Save finish(Save before, String digest, GameRunner.Result result) {
        GameJson.require(result.state(), 32768, json);
        String encoded = json.writeValueAsString(result);
        if (json.writeValueAsBytes(result).length > 98304) {
            throw new GameException("GAME_LIMIT", "Game result exceeds its storage limit");
        }
        int changed = jdbc.update(
                "update game_saves set state=?,revision=revision+1,last_digest=?,last_result=?,pending_digest=null,pending_until=null,pending_id=null,updated_at=? "
                        + "where account_id=? and save_id=? and revision=? and pending_digest=? and pending_id=?",
                json.writeValueAsString(result.state()),
                digest,
                encoded,
                Timestamp.from(clock.instant()),
                before.account(),
                before.id(),
                before.revision(),
                digest,
                before.pendingId());
        if (changed != 1) {
            throw conflict();
        }
        return get(before.account(), before.id());
    }

    void failed(Save before, String digest) {
        jdbc.update(
                "update game_saves set pending_digest=null,pending_until=null,pending_id=null where account_id=? and save_id=? and revision=? and pending_digest=? and pending_id=?",
                before.account(),
                before.id(),
                before.revision(),
                digest,
                before.pendingId());
    }

    boolean remove(UUID account, UUID id) {
        return jdbc.update("delete from game_saves where account_id=? and save_id=?", account, id) != 0;
    }

    void requireIdle(Save save) {
        if (pending(save)) {
            throw new GameException("GAME_BUSY", "This save already has a step in progress");
        }
    }

    boolean pending(Save save) {
        return save.pendingUntil() != null && save.pendingUntil().isAfter(clock.instant());
    }

    static void requireVersion(Save save, GameLibrary.Published game) {
        if (!save.version().equals(game.version())) {
            throw new GameException(
                    "GAME_UPDATED", "This game's package changed; start a new save for the current version");
        }
    }

    static GameException conflict() {
        return new GameException(
                "SAVE_CONFLICT", "The save or request counter changed; read its current revision before another move");
    }

    private Save row(ResultSet row, int index) throws SQLException {
        String state = row.getString(7);
        String result = row.getString(11);
        Timestamp pending = row.getTimestamp(13);
        return new Save(
                row.getObject(1, UUID.class),
                row.getObject(2, UUID.class),
                row.getLong(3),
                new WorkspaceId(row.getObject(4, UUID.class)),
                row.getObject(5, UUID.class),
                row.getString(6),
                state == null ? null : json.readTree(state),
                row.getLong(8),
                row.getLong(9),
                row.getString(10),
                result == null ? null : json.readValue(result, GameRunner.Result.class),
                row.getString(12),
                pending == null ? null : pending.toInstant(),
                row.getTimestamp(14).toInstant(),
                row.getObject(15, UUID.class),
                row.getString(16));
    }

    record Save(
            UUID id,
            UUID account,
            long creationRequest,
            WorkspaceId workspace,
            UUID articleId,
            String version,
            JsonNode state,
            long revision,
            long seed,
            String lastDigest,
            GameRunner.Result result,
            String pendingDigest,
            Instant pendingUntil,
            Instant updatedAt,
            UUID pendingId,
            String creationDigest) {
        GameScope.Target target() {
            return new GameScope.Target(workspace, articleId);
        }
    }
}
