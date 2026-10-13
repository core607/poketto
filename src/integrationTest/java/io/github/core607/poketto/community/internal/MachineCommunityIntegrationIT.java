package io.github.core607.poketto.community.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.MachineCommunity;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class MachineCommunityIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:10Z"), ZoneOffset.UTC);
    private final UUID article = UUID.randomUUID();
    private final Snapshots snapshots = new Snapshots();
    private JdbcTemplate jdbc;
    private AuthService auth;
    private MachineAccounts accounts;
    private SitePolicyService policy;
    private AuthPrincipal owner;
    private AuthPrincipal writer;
    private AuthPrincipal key;
    private WorkspaceId workspace;
    private Community community;
    private MachineCommunity machines;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(data).load().migrate();
        jdbc = new JdbcTemplate(data);
        jdbc.execute(
                "truncate community_notifications,community_reports,community_comments,community_blocks,community_follows,community_relations,community_rate_limits,community_agent_signatures,workspaces,auth_accounts cascade");
        jdbc.update("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default,public_slug,public_delivery) "
                        + "values (?,'Machine community fixture',true,'street',true)",
                workspace.value());
        var transactions = new DataSourceTransactionManager(data);
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, transactions, passwords, event -> {}, clock);
        owner = auth.initializeOwner("owner", "Machine-community-fixture-2026!");
        writer = AccountFixtures.create(auth, "writer", "Machine-community-fixture-2026!");
        auth.acceptInvitation(
                writer, auth.createInvitation(owner, workspace, Set.of()).token());
        var human = new Accounts(jdbc, transactions);
        accounts = new MachineAccounts(jdbc, human, auth, transactions);
        policy = new SitePolicyService(jdbc, human, auth, transactions);
        policy.change(owner, writer.accountId(), SiteGroup.CREATOR, "Synthetic approval");
        key = auth.authenticateApiKey(auth.createApiKey(owner, workspace, writer.accountId(), Set.of())
                .token());
        WorkspacePublications publications = CommunityPublicationFixture.publications(jdbc);
        var guard = new PublicationGuard(jdbc, publications);
        var identities = new CommunityAccounts(jdbc, human);
        var configuration = new CommunityConfiguration(clock);
        machines = configuration.machineCommunity(
                jdbc, accounts, identities, guard, publications, snapshots, transactions);
        community = configuration.community(
                jdbc,
                human,
                identities,
                guard,
                publications,
                snapshots,
                transactions,
                JsonMapper.builder().findAndAddModules().build(),
                mock(CommunityCorrections.class));
        snapshots.value = snapshot(article);
    }

    @Test
    void onlyTheHolderCanConsentAndTheServerKeepsAttributionOnIdempotentRetries() {
        var input = input("<img src=x onerror=alert(1)> plain text");
        assertThatThrownBy(() -> machines.comment(key, workspace, "street/paper", input, "client"))
                .isInstanceOf(CommunityException.class)
                .hasMessageContaining("OWNER_CONSENT_REQUIRED");
        assertThatThrownBy(() -> accounts.set(owner, key.subjectId(), Set.of(MachinePermission.COMMENT)))
                .isInstanceOf(AuthException.class);
        consent();
        machines.sign(key, workspace, "小猫落款");
        UUID id = machines.comment(key, workspace, "street/paper", input, "Client A");
        machines.sign(key, workspace, "Later signature");
        assertThat(machines.comment(key, workspace, "street/paper", input, "Client B"))
                .isEqualTo(id);
        Community.Comment result = community
                .article(null, "street", article, null, 0)
                .comments()
                .items()
                .getFirst();
        assertThat(result.author().accountId()).isEqualTo(writer.accountId());
        assertThat(result.agentPosted()).isTrue();
        assertThat(result.body())
                .contains(input.body(), "小猫落款", "由 agent 代发", "Client A")
                .doesNotContain("Later signature", "Client B");
        assertThat(jdbc.queryForObject("select count(*) from community_notifications", Long.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> community.comment(key, "street", article, input("bypass")))
                .isInstanceOf(AuthException.class);
        accounts.set(writer, key.subjectId(), Set.of());
        assertThatThrownBy(() -> machines.comment(key, workspace, "street/paper", input, "client"))
                .hasMessageContaining("OWNER_CONSENT_REQUIRED");
    }

    @Test
    void concurrentRetriesShareOneCommentAndOneAllowance() throws Exception {
        consent();
        var input = input("One paper");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> machines.comment(key, workspace, "street/paper", input, "A"));
            var second = pool.submit(() -> machines.comment(key, workspace, "street/paper", input, "B"));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from community_comments", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "select used from community_rate_limits where account_id=?", Integer.class, writer.accountId()))
                .containsOnly(1);
    }

    @Test
    void wallAndWritesFollowModerationBlockingWithdrawalAndArticleIdentity() {
        consent();
        UUID id = machines.comment(key, workspace, "street/paper", input("Visible on the wall"), "client");
        assertThat(accounts.withCreator(key, workspace, machines::wall)).hasSize(1);
        community.moderateComment(owner, id);
        assertThat(accounts.withCreator(key, workspace, machines::wall)).isEmpty();
        UUID root = community.comment(owner, "street", article, input("Human root"));
        community.block(owner, writer.accountId(), true);
        var reply = new Community.CommentInput(UUID.randomUUID(), root, "A reply");
        assertThatThrownBy(() -> machines.comment(key, workspace, "street/paper", reply, "client"))
                .hasMessageContaining("REPLY_UNAVAILABLE");
        jdbc.update("update workspaces set public_delivery=false where workspace_id=?", workspace.value());
        assertThat(accounts.withCreator(key, workspace, machines::wall)).isEmpty();
        assertThatThrownBy(() -> machines.comment(key, workspace, "street/paper", input("closed"), "client"))
                .isInstanceOf(CommunityException.class);
        jdbc.update("update workspaces set public_delivery=true where workspace_id=?", workspace.value());
        snapshots.value = snapshot(null);
        assertThatThrownBy(() -> machines.comment(key, workspace, "street/paper", input("no ID"), "client"))
                .isInstanceOf(CommunityException.class);
        assertThat(jdbc.queryForObject("select count(*) from community_comments", Long.class))
                .isEqualTo(2);
    }

    @Test
    void machineRateAndAccountEligibilityAreBothRechecked() {
        consent();
        for (int index = 0; index < 5; index++) {
            machines.comment(key, workspace, "street/paper", input("Paper " + index), "client");
        }
        assertThatThrownBy(() -> machines.comment(key, workspace, "street/paper", input("Too many"), "client"))
                .hasMessageContaining("LIMIT_REACHED");
        assertThat(jdbc.queryForObject("select count(*) from community_comments", Long.class))
                .isEqualTo(5);
        policy.change(owner, writer.accountId(), SiteGroup.COMMUNITY, "Synthetic downgrade");
        assertThatThrownBy(() -> machines.sign(key, workspace, "No longer a creator"))
                .isInstanceOf(AuthException.class);
    }

    private void consent() {
        accounts.set(writer, key.subjectId(), Set.of(MachinePermission.COMMENT));
    }

    @Test
    void aMissingAuthorDoesNotHideTheRestOfTheStreet() {
        consent();
        UUID orphan = machines.comment(key, workspace, "street/paper", input("Orphan"), "client");
        machines.comment(key, workspace, "street/paper", input("Available"), "client");
        jdbc.update("update community_comments set author_id=? where comment_id=?", UUID.randomUUID(), orphan);
        assertThat(accounts.withCreator(key, workspace, machines::wall))
                .hasSize(1)
                .allSatisfy(paper -> assertThat(paper.excerpt()).contains("Available"));
    }

    @Test
    void signatureChangesHaveTheirOwnAccountAllowanceAndDoNotConsumeCommentAllowance() {
        consent();
        for (int index = 0; index < 10; index++) {
            machines.sign(key, workspace, "Signature " + index);
        }
        assertThatThrownBy(() -> machines.sign(key, workspace, "Too many")).hasMessageContaining("LIMIT_REACHED");
        assertThat(jdbc.queryForObject("select signature from community_agent_signatures", String.class))
                .isEqualTo("Signature 9");
        machines.comment(key, workspace, "street/paper", input("Still allowed"), "client");
    }

    private static Community.CommentInput input(String text) {
        return new Community.CommentInput(UUID.randomUUID(), null, text);
    }

    private PublicContentSnapshot snapshot(UUID id) {
        var paper = new PublicArticle(
                "public/paper.md",
                "/paper",
                "Public paper",
                "Evidence",
                List.of(),
                clock.instant(),
                clock.instant(),
                false,
                "Author",
                id,
                false);
        return new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                clock.instant(),
                clock.instant().plusSeconds(600),
                List.of(paper));
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private PublicContentSnapshot value;

        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("Machine comments cannot fetch Git");
        }

        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("Machine comments cannot fetch Git");
        }

        public PublicContentSnapshot current(WorkspaceId workspace) {
            return value;
        }

        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            return operation.apply(value);
        }
    }
}
