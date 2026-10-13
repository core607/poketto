package io.github.core607.poketto.games.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.games.GameSaves;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import io.github.core607.poketto.workspace.internal.CommunityPublicationFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Real account/publication transactions and persistence; the bounded rule runner is a fixture, not sandbox evidence. */
@Testcontainers
class GameSavesIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private final JsonMapper json = JsonMapper.shared();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);
    private final UUID articleId = UUID.randomUUID();
    private final GameRunner runner = mock(GameRunner.class);
    private final GameLibrary library = mock(GameLibrary.class);
    private final Snapshots snapshots = new Snapshots();
    private JdbcTemplate jdbc;
    private AuthService auth;
    private MachineAccounts accounts;
    private AuthPrincipal owner;
    private AuthPrincipal key;
    private WorkspaceId workspace;
    private GameLibrary.Published game;
    private GameScope scope;
    private GameSaveStore store;
    private GameSaves games;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(data).load().migrate();
        jdbc = new JdbcTemplate(data);
        jdbc.execute("truncate game_accounts,game_saves,workspaces,auth_accounts cascade");
        jdbc.update("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default,public_slug,public_delivery) values (?,'Game fixture',true,'street',true)",
                workspace.value());
        var transactions = new DataSourceTransactionManager(data);
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, transactions, passwords, event -> {}, clock);
        owner = auth.initializeOwner("game-owner", "Game-fixture-password-2026!");
        var people = new Accounts(jdbc, transactions);
        accounts = new MachineAccounts(jdbc, people, auth, transactions);
        key = auth.authenticateApiKey(
                auth.createApiKey(owner, workspace, owner.accountId(), Set.of()).token());
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.GAME_SAVE));
        WorkspacePublications publications = CommunityPublicationFixture.publications(jdbc);
        scope = new GameScope(
                new CommunityAccounts(jdbc, people),
                accounts,
                new PublicationGuard(jdbc, publications),
                library,
                transactions);
        store = new GameSaveStore(jdbc, json, clock);
        games = new DefaultGameSaves(scope, store, library, runner, publications, snapshots, json);
        game = new GameLibrary.Published(
                workspace,
                articleId,
                "Fixture puzzle",
                "sha256:" + "a".repeat(64),
                "Advance one step",
                new GameBundle(1, "fixture", null, Map.of()));
        var article = new PublicArticle(
                "public/game.md",
                "/game",
                "Fixture puzzle",
                "Intro",
                List.of(),
                clock.instant(),
                clock.instant(),
                false,
                "Author",
                articleId,
                false);
        snapshots.value = new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                clock.instant(),
                clock.instant().plusSeconds(600),
                List.of(article));
        when(library.find(any(WorkspaceId.class), any())).thenAnswer(call -> Optional.of(game));
        when(library.withCurrent(any(), any(), any())).thenAnswer(call -> {
            Function<GameLibrary.Published, Object> action = call.getArgument(2);
            return action.apply(game);
        });
        when(runner.run(any(), any(), any())).thenAnswer(call -> result(call.getArgument(2)));
    }

    @Test
    void browserUploadAndResumeNeverRunAJobAndAgentCanContinueTheSameSave() {
        GameSaves.View saved = games.store(owner, upload(null, "1", null, 4));
        assertThat(games.store(owner, upload(null, "1", null, 4)).saveId()).isEqualTo(saved.saveId());
        assertThat(games.load(owner, saved.saveId())
                        .result()
                        .state()
                        .path("turn")
                        .intValue())
                .isEqualTo(4);
        verifyNoInteractions(runner);
        GameSaves.View observed = games.peek(key, workspace, saved.saveId());
        assertThat(observed.result().observation().text()).isEqualTo("Turn 4");
        GameSaves.View advanced = games.press(key, workspace, saved.saveId(), "next", saved.revision());
        assertThat(advanced.revision()).isEqualTo("2");
        assertThat(games.load(owner, saved.saveId())
                        .result()
                        .state()
                        .path("turn")
                        .intValue())
                .isEqualTo(5);
        assertThatThrownBy(() -> games.store(owner, upload(saved.saveId(), null, "1", 99)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void anUploadPreparedUnderAnotherAccountCannotBecomeANewSaveAfterLoginChanges() {
        GameSaves.Upload prepared = upload(null, "1", null, 4);
        var otherAccount = new GameSaves.Upload(
                UUID.randomUUID(),
                prepared.saveId(),
                prepared.creationRequest(),
                prepared.expectedRevision(),
                prepared.space(),
                prepared.articleId(),
                prepared.packageVersion(),
                prepared.state());
        assertThatThrownBy(() -> games.store(owner, otherAccount))
                .isInstanceOfSatisfying(
                        GameException.class, error -> assertThat(error.code()).isEqualTo("SAVE_CONFLICT"));
        assertThat(games.index(owner, null).nextCreationRequest()).isEqualTo("1");
        assertThat(games.index(owner, null).items()).isEmpty();
        verifyNoInteractions(runner);
    }

    @Test
    void concurrentAndLateRetriesNeverExecuteOrCommitTheMoveTwice() throws Exception {
        GameSaves.View created = games.play(key, workspace, "street/game", "1");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return result(call.getArgument(2));
                })
                .when(runner)
                .run(any(), any(), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> games.press(key, workspace, created.saveId(), "next", "1"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> games.press(key, workspace, created.saveId(), "next", "1"))
                    .hasMessageContaining("in progress");
            release.countDown();
            GameSaves.View finished = first.get(5, TimeUnit.SECONDS);
            assertThat(games.press(key, workspace, created.saveId(), "next", "1"))
                    .isEqualTo(finished);
            assertThatThrownBy(() -> games.press(key, workspace, created.saveId(), "different", "1"))
                    .hasMessageContaining("changed");
        }
        verify(runner, times(2)).run(any(), any(), any());
        assertThat(jdbc.queryForObject("select revision from game_saves where save_id=?", Long.class, created.saveId()))
                .isEqualTo(2);
    }

    @Test
    void revokedConsentDuringAJobDoesNotBlockRevocationOrPersistItsResult() throws Exception {
        GameSaves.View created = games.play(key, workspace, "street/game", "1");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return result(call.getArgument(2));
                })
                .when(runner)
                .run(any(), any(), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var move = pool.submit(() -> games.press(key, workspace, created.saveId(), "next", "1"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> accounts.change(owner, key.subjectId(), MachinePermission.GAME_SAVE, false));
            assertThat(revoke.get(3, TimeUnit.SECONDS)).isEmpty();
            release.countDown();
            assertThatThrownBy(() -> move.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(GameException.class);
        }
        assertThat(games.load(owner, created.saveId()).revision()).isEqualTo("1");
        assertThat(jdbc.queryForObject(
                        "select pending_id is null from game_saves where save_id=?", Boolean.class, created.saveId()))
                .isTrue();
    }

    @Test
    void removedSavesCannotBeRecreatedByOldRequestsAndOtherAccountsCannotReadThem() {
        GameSaves.View created = games.play(key, workspace, "street/game", "1");
        AuthPrincipal stranger = AccountFixtures.create(auth, "stranger", "Game-fixture-password-2026!");
        assertThatThrownBy(() -> games.load(stranger, created.saveId())).hasMessageContaining("no such game save");
        assertThat(games.remove(stranger, null, created.saveId())).isFalse();
        assertThat(games.remove(owner, null, created.saveId())).isTrue();
        assertThatThrownBy(() -> games.play(key, workspace, "street/game", "1")).hasMessageContaining("removed");
        assertThat(games.index(owner, null).nextCreationRequest()).isEqualTo("2");
        assertThat(games.play(key, workspace, "street/game", "2").saveId()).isNotEqualTo(created.saveId());
        assertThat(jdbc.queryForObject("select count(*) from game_accounts", Long.class))
                .isEqualTo(1);
    }

    @Test
    void changedPackagesSuspendSavesAndWithdrawalRejectsCloudResumeAndAgentSteps() {
        GameSaves.View saved = games.store(owner, upload(null, "1", null, 1));
        game = new GameLibrary.Published(
                workspace, articleId, game.title(), "sha256:" + "b".repeat(64), game.help(), game.bundle());
        assertThatThrownBy(() -> games.load(owner, saved.saveId())).hasMessageContaining("package changed");
        assertThatThrownBy(() -> games.press(key, workspace, saved.saveId(), "next", "1"))
                .hasMessageContaining("package changed");
        jdbc.update("update workspaces set public_delivery=false where workspace_id=?", workspace.value());
        assertThatThrownBy(() -> games.load(owner, saved.saveId())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(runner);
    }

    @Test
    void expiredAttemptCannotClearOrOverwriteAReplacementWithTheSameLogicalRequest() {
        GameSaves.View saved = games.store(owner, upload(null, "1", null, 0));
        GameSaveStore.Save before = scope.account(
                owner, null, account -> store.reserve(store.get(account, saved.saveId()), 1, "d".repeat(64)));
        var later = new GameSaveStore(jdbc, json, Clock.fixed(clock.instant().plusSeconds(60), ZoneOffset.UTC));
        GameSaveStore.Save replacement = scope.account(
                owner, null, account -> later.reserve(later.get(account, saved.saveId()), 1, "d".repeat(64)));
        store.failed(before, "d".repeat(64));
        assertThat(store.get(owner.accountId(), saved.saveId()).pendingId()).isEqualTo(replacement.pendingId());
        GameRunner.Result stale = result(new GameRunner.Request("init", null, null, 1L));
        assertThatThrownBy(() -> scope.account(owner, null, account -> store.finish(before, "d".repeat(64), stale)))
                .hasMessageContaining("changed");
        assertThat(store.get(owner.accountId(), saved.saveId()).revision()).isEqualTo(1);
    }

    private GameSaves.Upload upload(UUID id, String creation, String revision, int turn) {
        return new GameSaves.Upload(
                owner.accountId(),
                id,
                creation,
                revision,
                "street",
                articleId,
                game.version(),
                json.createObjectNode().put("turn", turn));
    }

    private GameRunner.Result result(GameRunner.Request request) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
        int turn =
                request.mode().equals("init") ? 0 : request.state().path("turn").intValue();
        if (request.mode().equals("act")) {
            turn++;
        }
        return new GameRunner.Result(
                json.createObjectNode().put("turn", turn),
                new GameRunner.Observation("Turn " + turn, List.of(new GameRunner.Action("next", "Advance")), false),
                null);
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private PublicContentSnapshot value;

        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("Game saves cannot fetch Git");
        }

        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("Game saves cannot fetch Git");
        }

        public PublicContentSnapshot current(WorkspaceId workspace) {
            return value;
        }

        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            return operation.apply(value);
        }
    }
}
