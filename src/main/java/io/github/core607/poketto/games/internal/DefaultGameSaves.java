package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameJson;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.games.GameSaves;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class DefaultGameSaves implements GameSaves {
    private final GameScope scope;
    private final GameSaveStore saves;
    private final GameLibrary library;
    private final GameRunner runner;
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    DefaultGameSaves(
            GameScope scope,
            GameSaveStore saves,
            GameLibrary library,
            GameRunner runner,
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            ObjectMapper json) {
        this.scope = scope;
        this.saves = saves;
        this.library = library;
        this.runner = runner;
        this.publications = publications;
        this.snapshots = new WebsiteContentSnapshots(snapshots, publications);
        this.json = json;
    }

    @Override
    public Index index(AuthPrincipal actor, WorkspaceId connection) {
        return scope.account(
                actor,
                connection,
                account -> new Index(
                        account,
                        saves.list(account).stream()
                                .map(save -> {
                                    Optional<GameLibrary.Published> game =
                                            library.find(save.workspace(), save.articleId());
                                    String code = game.isEmpty()
                                            ? "GAME_UNAVAILABLE"
                                            : !game.orElseThrow().version().equals(save.version())
                                                    ? "GAME_UPDATED"
                                                    : saves.pending(save)
                                                            ? "GAME_BUSY"
                                                            : save.state() == null ? "GAME_FAILED" : "READY";
                                    return new Summary(
                                            save.id(),
                                            Long.toString(save.revision()),
                                            save.workspace().value(),
                                            save.articleId(),
                                            game.map(GameLibrary.Published::title)
                                                    .orElse(""),
                                            code);
                                })
                                .toList(),
                        Long.toString(saves.nextRequest(account))));
    }

    @Override
    public View play(AuthPrincipal actor, WorkspaceId connection, String reference, String creationRequest) {
        Objects.requireNonNull(connection, "Agent game execution requires a credential workspace");
        long request = GameSaves.counter(creationRequest, false);
        Prepared prepared = scope.published(actor, connection, account -> target(reference), (account, game) -> {
            String digest = digest(
                    new Operation("init", game.workspaceId(), game.articleId(), game.version(), request, null, null));
            return create(account, request, game, digest);
        });
        return execute(
                actor,
                connection,
                prepared,
                new GameRunner.Request("init", null, null, prepared.save().seed()));
    }

    @Override
    public View press(AuthPrincipal actor, WorkspaceId connection, UUID save, String action, String expectedRevision) {
        Objects.requireNonNull(connection, "Agent game execution requires a credential workspace");
        long revision = GameSaves.counter(expectedRevision, true);
        GameBundle.text(action, 128, "Game action");
        Prepared prepared = scope.published(
                actor, connection, account -> saves.get(account, save).target(), (account, game) -> {
                    GameSaveStore.Save before = saves.get(account, save);
                    GameSaveStore.requireVersion(before, game);
                    String digest = digest(new Operation(
                            "act", before.workspace(), before.articleId(), before.version(), revision, action, null));
                    if (revision == before.revision() - 1 && digest.equals(before.lastDigest())) {
                        return new Prepared(before, game, digest, true);
                    }
                    requireState(before);
                    return new Prepared(saves.reserve(before, revision, digest), game, digest, false);
                });
        return execute(
                actor,
                connection,
                prepared,
                new GameRunner.Request("act", prepared.save().state(), action, null));
    }

    @Override
    public View peek(AuthPrincipal actor, WorkspaceId connection, UUID save) {
        Objects.requireNonNull(connection, "Agent game execution requires a credential workspace");
        Prepared before = scope.published(
                actor, connection, account -> saves.get(account, save).target(), (account, game) -> {
                    GameSaveStore.Save selected = saves.get(account, save);
                    GameSaveStore.requireVersion(selected, game);
                    saves.requireIdle(selected);
                    requireState(selected);
                    return new Prepared(selected, game, "", false);
                });
        GameRunner.Result result = runner.run(
                identity(actor, connection),
                before.game().bundle(),
                new GameRunner.Request("observe", before.save().state(), null, null));
        return scope.published(actor, connection, account -> before.save().target(), (account, game) -> {
            GameSaveStore.Save current = saves.get(account, save);
            GameSaveStore.requireVersion(current, game);
            if (current.revision() != before.save().revision()) {
                throw GameSaveStore.conflict();
            }
            return view(current, game, result);
        });
    }

    @Override
    public View load(AuthPrincipal actor, UUID save) {
        return scope.published(actor, null, account -> saves.get(account, save).target(), (account, game) -> {
            GameSaveStore.Save selected = saves.get(account, save);
            GameSaveStore.requireVersion(selected, game);
            saves.requireIdle(selected);
            requireState(selected);
            return view(selected, game, selected.result());
        });
    }

    @Override
    public View store(AuthPrincipal actor, Upload input) {
        GameJson.require(input.state(), 32768, json);
        return scope.published(actor, null, account -> uploadTarget(account, input), (account, game) -> {
            if (!game.version().equals(input.packageVersion())) {
                throw new GameException(
                        "GAME_UPDATED", "The game package changed; this browser save cannot replace a current game");
            }
            Prepared selected =
                    input.saveId() == null ? createUpload(account, game, input) : updateUpload(account, game, input);
            if (selected.cached()) {
                return view(selected.save(), game, selected.save().result());
            }
            var result = new GameRunner.Result(
                    input.state(), new GameRunner.Observation("Browser progress saved.", List.of(), false), null);
            return view(saves.finish(selected.save(), selected.digest(), result), game, result);
        });
    }

    @Override
    public boolean remove(AuthPrincipal actor, WorkspaceId connection, UUID save) {
        return scope.account(actor, connection, account -> saves.remove(account, save));
    }

    private GameScope.Target uploadTarget(UUID account, Upload input) {
        if (!account.equals(input.accountId())) {
            throw GameSaveStore.conflict();
        }
        WorkspaceId workspace = publications
                .findPublished(input.space())
                .orElseThrow(() -> new GameException("GAME_UNAVAILABLE", "The game's website is unavailable"))
                .workspaceId();
        if (input.saveId() != null) {
            GameSaveStore.Save existing = saves.get(account, input.saveId());
            if (!workspace.equals(existing.workspace()) || !input.articleId().equals(existing.articleId())) {
                throw GameSaveStore.conflict();
            }
        }
        return new GameScope.Target(workspace, input.articleId());
    }

    private Prepared createUpload(UUID account, GameLibrary.Published game, Upload input) {
        long request = GameSaves.counter(input.creationRequest(), false);
        String digest = digest(new Operation(
                "web-create", game.workspaceId(), game.articleId(), game.version(), request, null, input.state()));
        return create(account, request, game, digest);
    }

    private Prepared updateUpload(UUID account, GameLibrary.Published game, Upload input) {
        GameSaveStore.Save before = saves.get(account, input.saveId());
        GameSaveStore.requireVersion(before, game);
        long revision = GameSaves.counter(input.expectedRevision(), true);
        String digest = digest(new Operation(
                "web-save", before.workspace(), before.articleId(), before.version(), revision, null, input.state()));
        if (revision == before.revision() - 1 && digest.equals(before.lastDigest())) {
            return new Prepared(before, game, digest, true);
        }
        return new Prepared(saves.reserve(before, revision, digest), game, digest, false);
    }

    private Prepared create(UUID account, long request, GameLibrary.Published game, String digest) {
        Optional<GameSaveStore.Save> existing = saves.creation(account, request);
        if (existing.isPresent()) {
            GameSaveStore.Save selected = existing.orElseThrow();
            if (!digest.equals(selected.creationDigest())) {
                throw GameSaveStore.conflict();
            }
            GameSaveStore.requireVersion(selected, game);
            saves.requireIdle(selected);
            requireState(selected);
            return new Prepared(selected, game, digest, true);
        }
        long seed = Integer.toUnsignedLong(random.nextInt());
        return new Prepared(saves.create(account, request, game, seed, digest), game, digest, false);
    }

    private View execute(AuthPrincipal actor, WorkspaceId connection, Prepared prepared, GameRunner.Request request) {
        if (prepared.cached()) {
            return view(prepared.save(), prepared.game(), prepared.save().result());
        }
        boolean completed = false;
        try {
            GameRunner.Result result =
                    runner.run(identity(actor, connection), prepared.game().bundle(), request);
            View finished = scope.published(
                    actor, connection, account -> prepared.save().target(), (account, game) -> {
                        GameSaveStore.requireVersion(prepared.save(), game);
                        return view(saves.finish(prepared.save(), prepared.digest(), result), game, result);
                    });
            completed = true;
            return finished;
        } finally {
            // This token-bound cleanup changes no saved state and cannot clear a replacement attempt.
            if (!completed) {
                saves.failed(prepared.save(), prepared.digest());
            }
        }
    }

    private GameScope.Target target(String reference) {
        int split = reference.indexOf('/');
        if (split < 1 || reference.length() > 2304) {
            throw new IllegalArgumentException("A game reference is space/route");
        }
        WorkspaceId workspace = publications
                .findPublished(reference.substring(0, split))
                .orElseThrow(() -> new GameException("GAME_UNAVAILABLE", "The game's website is unavailable"))
                .workspaceId();
        return snapshots.withCurrent(workspace, snapshot -> {
            PublicArticle article = snapshot.articles().stream()
                    .filter(value -> value.route().equals(reference.substring(split)) && value.articleId() != null)
                    .findFirst()
                    .orElseThrow(
                            () -> new GameException("GAME_UNAVAILABLE", "The article has no current game identity"));
            return new GameScope.Target(workspace, article.articleId());
        });
    }

    private String digest(Operation operation) {
        return DocumentRevision.sha256(json.writeValueAsBytes(operation)).value();
    }

    private static GameRunner.Identity identity(AuthPrincipal actor, WorkspaceId connection) {
        return new GameRunner.Identity(actor.subjectId(), actor.accountId(), connection);
    }

    private static void requireState(GameSaveStore.Save save) {
        if (save.state() == null || save.result() == null) {
            throw new GameException(
                    "GAME_FAILED", "This game did not initialize; remove the failed save before a new attempt");
        }
    }

    private static View view(GameSaveStore.Save save, GameLibrary.Published game, GameRunner.Result result) {
        return new View(
                save.id(),
                Long.toString(save.revision()),
                save.version(),
                game.title(),
                save.workspace().value(),
                save.articleId(),
                result);
    }

    private record Prepared(GameSaveStore.Save save, GameLibrary.Published game, String digest, boolean cached) {}

    private record Operation(
            String mode,
            WorkspaceId workspace,
            UUID articleId,
            String version,
            long revision,
            String action,
            JsonNode state) {}
}
