package io.github.core607.poketto.community.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.Community.CommentInput;
import io.github.core607.poketto.community.Community.Relation;
import io.github.core607.poketto.community.Community.ReportInput;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import io.github.core607.poketto.workspace.internal.CommunityPublicationFixture;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class CommunityIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private static final String PASSWORD = "Community-fixture-password-2026!";
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private PasswordEncoder passwords;
    private AuthService auth;
    private SitePolicyService policy;
    private Community community;
    private WorkspaceId workspace;
    private AuthPrincipal owner;
    private AuthPrincipal member;
    private AuthPrincipal other;
    private AuthPrincipal viewer;
    private final UUID articleId = UUID.randomUUID();
    private final UUID secondId = UUID.randomUUID();
    private final TestSnapshots snapshots = new TestSnapshots();

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "truncate community_notifications,community_reports,community_comments,community_blocks,community_follows,community_relations,community_rate_limits,workspaces,auth_accounts cascade");
        jdbc.update("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug,public_delivery,is_default) values (?,'Public space','community-test',true,true)",
                workspace.value());
        transactions = new DataSourceTransactionManager(source);
        passwords = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, transactions, passwords, event -> {}, Clock.systemUTC());
        owner = auth.initializeOwner("community-owner", PASSWORD);
        member = account("member", SiteGroup.COMMUNITY);
        other = account("other", SiteGroup.COMMUNITY);
        viewer = account("viewer", SiteGroup.VIEWER);
        var accounts = new Accounts(jdbc, transactions);
        policy = new SitePolicyService(jdbc, accounts, auth, transactions);
        WorkspacePublications publications = CommunityPublicationFixture.publications(jdbc);
        snapshots.snapshot = snapshot("/first", articleId, secondId);
        community = community(accounts, publications, snapshots);
    }

    private Community community(Accounts accounts, WorkspacePublications publications, TestSnapshots source) {
        var configuration = new CommunityConfiguration();
        var communityAccounts = new CommunityAccounts(jdbc, accounts);
        var guard = new PublicationGuard(jdbc, publications);
        return configuration.community(
                jdbc,
                accounts,
                communityAccounts,
                guard,
                publications,
                source,
                transactions,
                JsonMapper.builder().findAndAddModules().build(),
                configuration.corrections(
                        jdbc,
                        auth,
                        communityAccounts,
                        guard,
                        publications,
                        source,
                        transactions,
                        (a, w, r, d, b, s) -> {
                            throw new UnsupportedOperationException();
                        }));
    }

    @Test
    void relationsAndCommentRetriesArePrivateIdempotentAndRespectGroupChanges() {
        UUID root = post(member, null, "hello");
        UUID request = UUID.randomUUID();
        UUID reply = community.comment(other, "community-test", articleId, new CommentInput(request, root, "reply"));
        assertThat(community.comment(other, "community-test", articleId, new CommentInput(request, root, "reply")))
                .isEqualTo(reply);
        assertThatThrownBy(() -> community.comment(
                        other, "community-test", articleId, new CommentInput(request, root, "changed")))
                .isInstanceOf(CommunityException.class);
        for (int i = 0; i < 2; i++) {
            community.relate(member, "community-test", articleId, Relation.LIKE, true);
            community.relate(member, "community-test", articleId, Relation.BOOKMARK, true);
            community.follow(member, "community-test", true);
        }
        assertThat(thread(member).likes()).isOne();
        assertThat(community.bookmarks(member, 0).items()).hasSize(1);
        assertThat(community.bookmarks(other, 0).items()).isEmpty();
        assertThat(community.notifications(owner, 0).items()).hasSize(1);
        assertThat(community.notifications(member, 0).items()).hasSize(1);
        assertThat(community.notifications(other, 0).items()).isEmpty();
        policy.change(owner, member.accountId(), SiteGroup.VIEWER, "downgrade");
        assertThatThrownBy(() -> post(member, null, "denied")).isInstanceOf(CommunityException.class);
        assertThatThrownBy(() -> community.relate(member, "community-test", secondId, Relation.LIKE, true))
                .isInstanceOf(CommunityException.class);
        assertThat(thread(null).comments().items()).hasSize(1);
        community.relate(member, "community-test", articleId, Relation.LIKE, false);
        community.deleteComment(member, root);
        assertThat(thread(null).comments().items().getFirst().deleted()).isTrue();
        assertThat(community
                        .article(null, "community-test", articleId, root, 0)
                        .comments()
                        .items())
                .hasSize(1);
        assertThatThrownBy(() -> community.moderateComment(viewer, root)).isInstanceOf(CommunityException.class);
        community.moderateComment(owner, root);
        assertThat(thread(null).comments().items()).isEmpty();
        assertThatThrownBy(() -> community.article(null, "community-test", articleId, root, 0))
                .isInstanceOf(CommunityException.class);
    }

    @Test
    void replyDepthBlocksReportsAndModerationApplyToEveryCommentEntry() {
        UUID root = post(member, null, "root");
        UUID reply = post(other, root, "reply");
        assertThatThrownBy(() -> post(member, reply, "nested")).isInstanceOf(CommunityException.class);
        community.block(member, other.accountId(), true);
        assertThat(community
                        .article(member, "community-test", articleId, root, 0)
                        .comments()
                        .items())
                .isEmpty();
        assertThat(community.notifications(member, 0).items()).isEmpty();
        assertThatThrownBy(() -> post(other, root, "blocked reply")).isInstanceOf(CommunityException.class);
        community.report(viewer, root, new ReportInput("Please review this root"));
        community.report(viewer, root, new ReportInput("Repeated report"));
        assertThat(community.reports(owner, 0).items()).hasSize(1);
        assertThatThrownBy(() -> community.reports(member, 0)).isInstanceOf(CommunityException.class);
        assertThatThrownBy(() -> community.deleteComment(viewer, root)).isInstanceOf(CommunityException.class);
        community.resolveReport(
                owner, community.reports(owner, 0).items().getFirst().position(), true);
        assertThat(thread(null).comments().items()).isEmpty();
        assertThatThrownBy(() -> community.article(null, "community-test", articleId, root, 0))
                .isInstanceOf(CommunityException.class);
        assertThat(community.notifications(owner, 0).items()).isEmpty();
        assertThat(community.reports(owner, 0).items()).isEmpty();
    }

    @Test
    void withdrawalAndIdentityAmbiguityHideHistoryWithoutDeletingIt() {
        post(member, null, "retained");
        community.relate(member, "community-test", articleId, Relation.LIKE, true);
        community.relate(member, "community-test", articleId, Relation.BOOKMARK, true);
        community.follow(member, "community-test", true);
        assertThat(community.feed(member, null).items()).hasSize(2);
        jdbc.update("update workspaces set public_delivery=false where workspace_id=?", workspace.value());
        assertThatThrownBy(() -> thread(null)).isInstanceOf(CommunityException.class);
        assertThat(community.feed(member, null).items()).isEmpty();
        assertThat(community.bookmarks(member, 0).items().getFirst().article()).isNull();
        assertThat(community.notifications(owner, 0).items()).isEmpty();
        long like = community.likes(member, 0).items().getFirst().position();
        assertThat(community.likes(member, 0).items().getFirst().article()).isNull();
        community.removeLike(other, like);
        assertThat(community.likes(member, 0).items()).hasSize(1);
        community.removeLike(member, like);
        assertThat(community.likes(member, 0).items()).isEmpty();
        jdbc.update("update workspaces set public_delivery=true where workspace_id=?", workspace.value());
        snapshots.snapshot = snapshot("/moved", articleId, secondId);
        assertThat(community.bookmarks(member, 0).items().getFirst().article().route())
                .isEqualTo("/moved");
        assertThat(thread(null).comments().items().getFirst().body()).isEqualTo("retained");
        snapshots.snapshot = snapshot("/moved", articleId, articleId);
        assertThatThrownBy(() -> thread(null)).isInstanceOf(CommunityException.class);
        snapshots.snapshot = snapshot("/restored", articleId, secondId);
        snapshots.expired = true;
        assertThatThrownBy(() -> thread(null)).isInstanceOf(ContentRepositoryException.class);
        assertThat(community.feed(member, null).items()).isEmpty();
        community.removeBookmark(
                member, community.bookmarks(member, 0).items().getFirst().position());
        community.unfollow(member, community.following(member).getFirst().position());
        assertThat(community.bookmarks(member, 0).items()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from community_comments", Long.class))
                .isOne();
    }

    @Test
    void quotasMachineIdentityAndNotificationOwnershipCannotBeBypassed() {
        UUID replay = UUID.randomUUID();
        community.comment(member, "community-test", articleId, new CommentInput(replay, null, "bounded"));
        for (int i = 1; i < 10; i++) {
            post(member, null, "bounded " + i);
        }
        assertThatThrownBy(() -> post(member, null, "too many")).isInstanceOf(CommunityException.class);
        community.comment(member, "community-test", articleId, new CommentInput(replay, null, "bounded"));
        assertThat(jdbc.queryForObject("select count(*) from community_comments", Long.class))
                .isEqualTo(10);
        long notification = community.notifications(owner, 0).items().getFirst().position();
        community.markRead(member, List.of(notification));
        assertThat(community.notifications(owner, 0).items().getFirst().read()).isFalse();
        community.markRead(owner, List.of(notification));
        assertThat(community.notifications(owner, 0).items().getFirst().read()).isTrue();
        var key = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        AuthPrincipal machine = auth.authenticateApiKey(key.token());
        assertThatThrownBy(() -> community.bookmarks(machine, 0)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> community.comment(
                        machine, "community-test", articleId, new CommentInput(UUID.randomUUID(), null, "machine")))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> new CommentInput(UUID.randomUUID(), null, "😸".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommentInput(UUID.randomUUID(), null, "\ud800"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chronologicalFeedCursorsAndSavedItemLimitsRemainBounded() {
        Instant now = Instant.now();
        List<PublicArticle> articles = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            articles.add(new PublicArticle(
                    "public/" + i + ".md",
                    "/" + i,
                    "Article " + i,
                    "body",
                    List.of(),
                    now.minusSeconds(i),
                    now,
                    false,
                    "",
                    UUID.randomUUID(),
                    false));
        }
        snapshots.snapshot =
                new PublicContentSnapshot(workspace, Optional.of("b".repeat(40)), now, now.plusSeconds(3600), articles);
        community.follow(member, "community-test", true);
        Community.Feed first = community.feed(member, null);
        Community.Feed second = community.feed(member, first.nextCursor());
        Community.Feed third = community.feed(member, second.nextCursor());
        assertThat(first.items()).hasSize(20);
        assertThat(second.items()).hasSize(20);
        assertThat(third.items()).hasSize(5);
        assertThat(second.items().getFirst().route()).isEqualTo("/20");
        assertThat(third.nextCursor()).isNull();
        assertThatThrownBy(() -> community.feed(member, "bnVsbA")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update(
                "insert into community_relations(account_id,workspace_id,article_id,kind) select ?,?,gen_random_uuid(),'BOOKMARK' from generate_series(1,1000)",
                member.accountId(),
                workspace.value());
        assertThatThrownBy(() -> community.relate(
                        member, "community-test", articles.getFirst().articleId(), Relation.BOOKMARK, true))
                .isInstanceOf(CommunityException.class);
        long position = community.bookmarks(member, 0).items().getFirst().position();
        community.removeBookmark(other, position);
        assertThat(jdbc.queryForObject("select count(*) from community_relations", Long.class))
                .isEqualTo(1000);
        community.removeBookmark(member, position);
        community.relate(member, "community-test", articles.getFirst().articleId(), Relation.BOOKMARK, true);
    }

    @Test
    void commitsBeforeReleasingSnapshotAndSerializesWithWithdrawal() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        snapshots.enter = () -> {
            entered.countDown();
            await(release);
        };
        snapshots.after = () -> assertThat(jdbc.queryForObject("select count(*) from community_comments", Long.class))
                .isOne();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var comment = workers.submit(() -> post(member, null, "before withdrawal"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawn = workers.submit(() -> auth.withAuthorization(owner, workspace, Set.of(), () -> {
                jdbc.update("update workspaces set public_delivery=false where workspace_id=?", workspace.value());
                return true;
            }));
            assertThatThrownBy(() -> withdrawn.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(comment.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(withdrawn.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> thread(null)).isInstanceOf(CommunityException.class);
    }

    @Test
    void ownerCredentialRecoveryDoesNotDeadlockCommentNotifications() throws Exception {
        var entered = new CountDownLatch(1);
        var ownerLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        snapshots.enter = () -> {
            entered.countDown();
            await(release);
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var comment = workers.submit(() -> post(member, null, "notify recovering owner"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var recovery = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.update(
                        "update auth_accounts set credential_version=credential_version+1 where account_id=?",
                        owner.accountId());
                ownerLocked.countDown();
                jdbc.queryForObject(
                        "select workspace_id from workspaces where workspace_id=? for update",
                        UUID.class,
                        workspace.value());
                return true;
            }));
            assertThat(ownerLocked.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(comment.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(recovery.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(jdbc.queryForObject("select count(*) from community_notifications", Long.class))
                .isOne();
    }

    @Test
    void concurrentSpacesRetainExactlyTheLatestThousandOwnerNotifications() throws Exception {
        WorkspaceId secondWorkspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug,public_delivery) values (?,'Second','community-second',true)",
                secondWorkspace.value());
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role) values (?,?,'OWNER')",
                secondWorkspace.value(),
                owner.accountId());
        var secondSnapshots = new TestSnapshots();
        PublicContentSnapshot original = snapshots.snapshot;
        secondSnapshots.snapshot = new PublicContentSnapshot(
                secondWorkspace, original.commit(), original.verifiedAt(), original.expiresAt(), original.articles());
        Community second = community(
                new Accounts(jdbc, transactions), CommunityPublicationFixture.publications(jdbc), secondSnapshots);
        seedFullInboxAndDeletionGate();
        try (var gate = jdbc.getDataSource().getConnection();
                var workers = Executors.newFixedThreadPool(2)) {
            gate.setAutoCommit(false);
            try (var statement = gate.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(93431,7)");
            }
            var firstWrite = workers.submit(() -> post(member, null, "first space"));
            var secondWrite = workers.submit(() -> second.comment(
                    other, "community-second", articleId, new CommentInput(UUID.randomUUID(), null, "second space")));
            try {
                awaitNotificationWriters();
            } finally {
                gate.commit();
            }
            assertThat(firstWrite.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(secondWrite.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            jdbc.execute("drop trigger inbox_test_gate on community_notifications");
            jdbc.execute("drop function inbox_test_gate()");
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from community_notifications where recipient_id=?",
                        Long.class,
                        owner.accountId()))
                .isEqualTo(1000);
        assertThat(community.notifications(owner, 0).items())
                .extracting(Community.Notification::excerpt)
                .contains("first space", "second space");
    }

    private void seedFullInboxAndDeletionGate() {
        jdbc.update(
                "insert into community_comments(comment_id,request_id,workspace_id,article_id,author_id,body,request_digest) "
                        + "select gen_random_uuid(),gen_random_uuid(),?,?,?,'seed','sha256:' || repeat('a',64) from generate_series(1,1000)",
                workspace.value(),
                articleId,
                member.accountId());
        jdbc.update(
                "insert into community_notifications(recipient_id,comment_id) select ?,comment_id from community_comments",
                owner.accountId());
        jdbc.execute("create function inbox_test_gate() returns trigger language plpgsql as $$ begin "
                + "perform pg_advisory_xact_lock(93431,7); return old; end $$");
        jdbc.execute("create trigger inbox_test_gate before delete on community_notifications "
                + "for each row execute function inbox_test_gate()");
    }

    private void awaitNotificationWriters() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' "
                            + "and (query like 'delete from community_notifications%' or query like '%community-inbox:%')",
                    Long.class);
            if (waiting == 2) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("both notification writers must reach the database gate");
    }

    private AuthPrincipal account(String name, SiteGroup group) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash,display_name,site_group) values (?,?,?,?,?)",
                id,
                name,
                passwords.encode(PASSWORD),
                "Display " + name,
                group.name());
        return auth.authenticatePassword(name, PASSWORD);
    }

    private UUID post(AuthPrincipal actor, UUID parent, String body) {
        return community.comment(actor, "community-test", articleId, new CommentInput(UUID.randomUUID(), parent, body));
    }

    private Community.Thread thread(AuthPrincipal actor) {
        return community.article(actor, "community-test", articleId, null, 0);
    }

    private PublicContentSnapshot snapshot(String route, UUID first, UUID second) {
        Instant now = Instant.now();
        return new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                now,
                now.plusSeconds(3600),
                List.of(
                        new PublicArticle(
                                "public/first.md",
                                route,
                                "First",
                                "# First",
                                List.of(),
                                now.minusSeconds(10),
                                now,
                                false,
                                "Byline",
                                first,
                                false),
                        new PublicArticle(
                                "public/second.md",
                                "/second",
                                "Second",
                                "# Second",
                                List.of(),
                                now,
                                now,
                                false,
                                "",
                                second,
                                false)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }

    private static final class TestSnapshots implements PublicContentSnapshots {
        private PublicContentSnapshot snapshot;
        private boolean expired;
        private Runnable enter = () -> {};
        private Runnable after = () -> {};

        @Override
        public void ensureReady(WorkspaceId workspace) {}

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            return current(workspace);
        }

        @Override
        public synchronized PublicContentSnapshot current(WorkspaceId workspace) {
            if (expired) {
                throw new ContentRepositoryException("fixture expired");
            }
            return snapshot;
        }

        @Override
        public synchronized <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            enter.run();
            T result = operation.apply(current(workspace));
            after.run();
            return result;
        }
    }
}
