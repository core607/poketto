package io.github.core607.poketto.community.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.Corrections;
import io.github.core607.poketto.community.Corrections.Proposal;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.ReviewedBodyEdits;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.internal.CommunityPublicationFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class CorrectionsIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private static final String PASSWORD = "Corrections-fixture-password-2026!";
    private static final String SPACE = "corrections-test";
    private static final String BODY = "# Essay\n\nThe river is 30 km long.\n";
    private JdbcTemplate jdbc;
    private PasswordEncoder passwords;
    private AuthService auth;
    private Community community;
    private Corrections corrections;
    private WorkspaceId workspace;
    private AuthPrincipal owner;
    private AuthPrincipal reader;
    private AuthPrincipal other;
    private final List<String> written = new ArrayList<>();
    private ReviewedBodyEdits.Result result = ReviewedBodyEdits.Result.APPLIED;
    private Runnable duringWrite = () -> {};

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "truncate community_notifications,community_corrections,community_comments,community_blocks,community_rate_limits,workspaces,auth_accounts cascade");
        jdbc.update("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug,public_delivery,is_default) values (?,'Public space',?,true,true)",
                workspace.value(),
                SPACE);
        var transactions = new DataSourceTransactionManager(source);
        passwords = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, transactions, passwords, event -> {}, Clock.systemUTC());
        owner = auth.initializeOwner("corrections-owner", PASSWORD);
        reader = account("reader", SiteGroup.COMMUNITY);
        other = account("other", SiteGroup.COMMUNITY);
        var accounts = new Accounts(jdbc, transactions);
        var communityAccounts = new CommunityAccounts(jdbc, accounts);
        var publications = CommunityPublicationFixture.publications(jdbc);
        var guard = new PublicationGuard(jdbc, publications);
        PublicContentSnapshots snapshots = new FixedSnapshots(snapshot());
        var configuration = new CommunityConfiguration();
        corrections = configuration.corrections(
                jdbc, auth, communityAccounts, guard, publications, snapshots, transactions, this::replace);
        community = configuration.community(
                jdbc,
                accounts,
                communityAccounts,
                guard,
                publications,
                snapshots,
                transactions,
                JsonMapper.builder().findAndAddModules().build(),
                (CommunityCorrections) corrections);
    }

    @Test
    void aProposalNeedsTheServedBaseAndOneOpenProposalPerReaderAndArticle() {
        assertThatThrownBy(() -> corrections.propose(reader, SPACE, proposal("/missing", BODY, "x")))
                .isInstanceOf(CommunityException.class);
        assertThatThrownBy(() -> corrections.propose(reader, SPACE, proposal("/essay", "# Older text\n", "x")))
                .isInstanceOfSatisfying(
                        CommunityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.BASE_CHANGED));
        assertThatThrownBy(() -> corrections.propose(reader, SPACE, proposal("/essay", BODY, BODY)))
                .isInstanceOf(IllegalArgumentException.class);
        var viewer = account("viewer", SiteGroup.VIEWER);
        assertThatThrownBy(() -> corrections.propose(viewer, SPACE, fixed()))
                .isInstanceOfSatisfying(
                        CommunityException.class,
                        failure ->
                                assertThat(failure.code()).isEqualTo(CommunityException.Code.PARTICIPATION_REQUIRED));

        var id = corrections.propose(reader, SPACE, fixed());
        assertThatThrownBy(() -> corrections.propose(reader, SPACE, fixed()))
                .isInstanceOfSatisfying(
                        CommunityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.REQUEST_CONFLICT));
        assertThat(corrections.mine(reader, SPACE, "/essay").orElseThrow().status())
                .isEqualTo("OPEN");
        var notice = community.notifications(owner, 0).items().getFirst();
        assertThat(notice.correctionId()).isEqualTo(id);
        assertThat(notice.event()).isEqualTo("PROPOSED");
        assertThat(notice.excerpt()).isEqualTo("the figure is 32 km");
        assertThat(notice.actor().displayName()).isEqualTo("Display reader");

        corrections.withdraw(reader, id);
        assertThatThrownBy(() -> corrections.withdraw(reader, id)).isInstanceOf(CommunityException.class);
        assertThat(corrections.mine(reader, SPACE, "/essay").orElseThrow().status())
                .isEqualTo("WITHDRAWN");
    }

    @Test
    void reviewNeedsPublishAndAcceptanceCreditsTheReaderInGitAndOnThePage() {
        var id = corrections.propose(reader, SPACE, fixed());
        assertThatThrownBy(() -> corrections.open(other, workspace, 0)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> corrections.accept(other, workspace, id)).isInstanceOf(AuthException.class);
        var review = corrections.open(owner, workspace, 0).items().getFirst();
        assertThat(review.currentBody()).isEqualTo(BODY);
        assertThat(review.stale()).isFalse();
        assertThat(review.title()).isEqualTo("Essay");

        assertThat(corrections.accept(owner, workspace, id)).isEqualTo(Corrections.Resolution.ACCEPTED);
        assertThat(written).containsExactly("/essay|account:" + reader.accountId() + "|" + BODY.replace("30", "32"));
        assertThat(corrections.credits(SPACE, "/essay")).containsExactly("Display reader");
        assertThat(community.notifications(reader, 0).items().getFirst().event())
                .isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> corrections.accept(owner, workspace, id)).isInstanceOf(CommunityException.class);

        var declined = corrections.propose(other, SPACE, fixed());
        corrections.decline(owner, workspace, declined);
        assertThat(community.notifications(other, 0).items().getFirst().event()).isEqualTo("DECLINED");
        result = ReviewedBodyEdits.Result.STALE;
        var stale = corrections.propose(other, SPACE, fixed());
        assertThat(corrections.accept(owner, workspace, stale)).isEqualTo(Corrections.Resolution.STALE);
        assertThat(corrections.mine(other, SPACE, "/essay").orElseThrow().status())
                .isEqualTo("STALE");
        assertThat(corrections.credits(SPACE, "/essay")).containsExactly("Display reader");

        community.block(reader, owner.accountId(), true);
        assertThat(corrections.credits(SPACE, "/essay")).isEmpty();
        assertThatThrownBy(() -> corrections.propose(reader, SPACE, fixed()))
                .isInstanceOfSatisfying(
                        CommunityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.DENIED));
    }

    @Test
    void anAcceptanceInProgressShutsOutDeclineAndWithdrawalAndAFailedWriteReopens() {
        var id = corrections.propose(reader, SPACE, fixed());
        duringWrite = () -> {
            assertThatThrownBy(() -> corrections.decline(owner, workspace, id))
                    .isInstanceOfSatisfying(
                            CommunityException.class,
                            failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.REQUEST_CONFLICT));
            assertThatThrownBy(() -> corrections.withdraw(reader, id))
                    .isInstanceOfSatisfying(
                            CommunityException.class,
                            failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.REQUEST_CONFLICT));
            assertThatThrownBy(() -> corrections.accept(owner, workspace, id))
                    .isInstanceOfSatisfying(
                            CommunityException.class,
                            failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.REQUEST_CONFLICT));
            assertThat(corrections.mine(reader, SPACE, "/essay").orElseThrow().status())
                    .isEqualTo("ACCEPTING");
            throw new IllegalStateException("remote unavailable");
        };
        assertThatThrownBy(() -> corrections.accept(owner, workspace, id)).hasMessage("remote unavailable");
        assertThat(corrections.mine(reader, SPACE, "/essay").orElseThrow().status())
                .isEqualTo("OPEN");

        duringWrite = () -> {};
        assertThat(corrections.accept(owner, workspace, id)).isEqualTo(Corrections.Resolution.ACCEPTED);
        assertThatThrownBy(() -> corrections.decline(owner, workspace, id))
                .isInstanceOfSatisfying(
                        CommunityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(CommunityException.Code.REQUEST_CONFLICT));

        var anonymous = corrections.propose(
                other, SPACE, new Proposal("/essay", digest(BODY), BODY.replace("30", "31"), "", false));
        assertThat(corrections.accept(owner, workspace, anonymous)).isEqualTo(Corrections.Resolution.ACCEPTED);
        assertThat(corrections.credits(SPACE, "/essay")).containsExactly("Display reader");
        assertThat(written.getLast()).startsWith("/essay|unnamed|");
    }

    @Test
    void aClaimStrandedForTenMinutesCountsAsOpenAgain() {
        var withdrawn = corrections.propose(reader, SPACE, fixed());
        strand(withdrawn);
        assertThat(corrections.mine(reader, SPACE, "/essay").orElseThrow().status())
                .isEqualTo("OPEN");
        assertThat(corrections.open(owner, workspace, 0).items())
                .extracting(Corrections.Review::id)
                .containsExactly(withdrawn);
        corrections.withdraw(reader, withdrawn);

        var declined = corrections.propose(reader, SPACE, fixed());
        strand(declined);
        corrections.decline(owner, workspace, declined);
        assertThat(corrections.mine(reader, SPACE, "/essay").orElseThrow().status())
                .isEqualTo("DECLINED");

        var retaken = corrections.propose(other, SPACE, fixed());
        strand(retaken);
        assertThat(corrections.accept(owner, workspace, retaken)).isEqualTo(Corrections.Resolution.ACCEPTED);
    }

    private void strand(UUID id) {
        jdbc.update(
                "update community_corrections set status='ACCEPTING',claimed_at=current_timestamp - interval '11 minutes',resolver_id=? where correction_id=?",
                owner.accountId(),
                id);
    }

    private ReviewedBodyEdits.Outcome replace(
            AuthPrincipal reviewer,
            WorkspaceId space,
            String route,
            String baseDigest,
            String body,
            Optional<WritePrincipal> suggestedBy) {
        assertThat(baseDigest).isEqualTo(digest(BODY));
        duringWrite.run();
        if (result == ReviewedBodyEdits.Result.APPLIED) {
            written.add(
                    route + "|" + suggestedBy.map(WritePrincipal::trailerValue).orElse("unnamed") + "|" + body);
        }
        return new ReviewedBodyEdits.Outcome(result, Optional.empty());
    }

    private static Proposal fixed() {
        return proposal("/essay", BODY, BODY.replace("30", "32"));
    }

    private static Proposal proposal(String route, String base, String body) {
        return new Proposal(route, digest(base), body, "the figure is 32 km", null);
    }

    private static String digest(String body) {
        return DocumentRevision.sha256(body.getBytes(StandardCharsets.UTF_8)).value();
    }

    private AuthPrincipal account(String name, SiteGroup group) {
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash,display_name,site_group) values (gen_random_uuid(),?,?,?,?)",
                name,
                passwords.encode(PASSWORD),
                "Display " + name,
                group.name());
        return auth.authenticatePassword(name, PASSWORD);
    }

    private PublicContentSnapshot snapshot() {
        Instant now = Instant.now();
        return new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                now,
                now.plusSeconds(3600),
                List.of(new PublicArticle(
                        "public/essay.md", "/essay", "Essay", BODY, List.of(), now, now, false, "", null, false)));
    }

    private record FixedSnapshots(PublicContentSnapshot snapshot) implements PublicContentSnapshots {
        @Override
        public void ensureReady(WorkspaceId workspace) {}

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            return snapshot;
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspace) {
            return snapshot;
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            return operation.apply(snapshot);
        }
    }
}
