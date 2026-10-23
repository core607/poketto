package io.github.core607.poketto.workspace.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.CreatorApplications;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SitePolicyIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AuthService auth;
    private Accounts accounts;
    private SitePolicyService policies;
    private JdbcWorkspacePublications publications;
    private WorkspaceId workspace;
    private AuthPrincipal administrator;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate workspaces,auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        var catalog = new JdbcWorkspaceCatalog(jdbc, transactions);
        catalog.ensureDefaultWorkspace();
        workspace = catalog.defaultWorkspace().id();
        var encoder = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, manager, encoder, event -> {}, Clock.systemUTC());
        accounts = new Accounts(jdbc, manager);
        policies = new SitePolicyService(jdbc, accounts, auth, manager);
        publications = new JdbcWorkspacePublications(jdbc);
        administrator = auth.initializeOwner("administrator", UUID.randomUUID().toString());
    }

    @Test
    void downgradeWithdrawsEveryOwnedWebsiteButPreservesGrantsAndDesiredSwitch() {
        AuthPrincipal creator = account("creator");
        assertThat(accounts.account(creator).group()).isEqualTo(SiteGroup.VIEWER);
        assertCode(() -> accounts.requireCreator(creator), AuthException.Code.DENIED);
        policies.change(administrator, creator.accountId(), SiteGroup.CREATOR, "Creator approved");
        auth.acceptInvitation(
                creator,
                auth.createInvitation(administrator, workspace, Set.of()).token());
        auth.changeMembership(administrator, workspace, creator.accountId(), MembershipRole.OWNER, true, Set.of());
        var key = auth.createApiKey(creator, workspace, creator.accountId(), AuthService.DEFAULT_AI_CAPABILITIES);
        WorkspaceId second = ownedSpace(creator);
        policies.change(administrator, creator.accountId(), SiteGroup.VIEWER, "Public content needs revision");
        for (WorkspaceId id : List.of(workspace, second)) {
            assertThat(publications.settings(id).enabled()).isTrue();
            assertThat(publications.settings(id).eligible()).isFalse();
            assertThatThrownBy(() -> publications.requireEnabled(id))
                    .isInstanceOf(PublicationUnavailableException.class);
            assertThatThrownBy(() -> transactions.execute(status -> publications.setEnabled(id, true)))
                    .isInstanceOf(PublicationUnavailableException.class);
        }
        assertThat(publications.publishedAfter(Optional.empty(), 100)).isEmpty();
        assertThat(publications.findPublished("home")).isEmpty();
        auth.authorize(creator, workspace, Capability.WRITE_PRIVATE);
        auth.authorize(auth.authenticateApiKey(key.token()), workspace, Capability.WRITE_PRIVATE);
        transactions.execute(status -> publications.setEnabled(second, false));
        policies.change(administrator, creator.accountId(), SiteGroup.CREATOR, "Revision accepted");
        publications.requireEnabled(workspace);
        assertThat(publications.settings(second).publiclyEnabled()).isFalse();
        assertThat(publications.settings(second).eligible()).isTrue();
        assertThat(policies.history(administrator, creator.accountId(), 0, 30).total())
                .isEqualTo(3);
    }

    @Test
    void nonownerGroupDoesNotHideTheSiteAndSiteAdminCannotReadPrivateSpace() {
        AuthPrincipal member = account("member");
        auth.acceptInvitation(
                member,
                auth.createInvitation(administrator, workspace, Set.of()).token());
        policies.change(administrator, member.accountId(), SiteGroup.COMMUNITY, "Participation approved");
        policies.change(administrator, member.accountId(), SiteGroup.VIEWER, "Participation withdrawn");
        publications.requireEnabled(workspace);
        AuthPrincipal otherAdmin = account("other-admin");
        policies.change(administrator, otherAdmin.accountId(), SiteGroup.ADMINISTRATOR, "Operator added");
        policies.requireAdministrator(otherAdmin);
        assertCode(() -> auth.authorize(otherAdmin, workspace, Capability.READ_PRIVATE), AuthException.Code.DENIED);
        assertCode(() -> policies.list(member, "", 0, 30), AuthException.Code.DENIED);
        assertCode(
                () -> policies.change(member, member.accountId(), SiteGroup.ADMINISTRATOR, "Self promotion"),
                AuthException.Code.DENIED);
    }

    @Test
    void concurrentAdministratorsCannotRemoveTheLastAdministrator() throws Exception {
        AuthPrincipal second = account("second-admin");
        policies.change(administrator, second.accountId(), SiteGroup.ADMINISTRATOR, "Operator added");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(administrator, second).stream()
                    .map(actor -> executor.submit(() -> {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("coordination timeout");
                        }
                        try {
                            policies.change(actor, actor.accountId(), SiteGroup.VIEWER, "Retiring operator");
                            return "changed";
                        } catch (AuthException exception) {
                            return exception.code().name();
                        }
                    }))
                    .toList();
            start.countDown();
            assertThat(List.of(
                            futures.get(0).get(10, TimeUnit.SECONDS),
                            futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("changed", "LAST_ADMINISTRATOR");
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_accounts where site_group='ADMINISTRATOR'", Integer.class))
                .isOne();
    }

    @Test
    void administratorsFilterAccountsAndSpacesAndReadSiteCounts() {
        AuthPrincipal creator = account("creator");
        AuthPrincipal reader = account("reader");
        policies.change(administrator, creator.accountId(), SiteGroup.CREATOR, "Creator approved");
        WorkspaceId second = ownedSpace(creator);
        jdbc.update(
                "update auth_accounts set created_at=created_at-interval '30 days' where account_id=?",
                administrator.accountId());

        var creators =
                policies.list(administrator, "", SiteGroup.CREATOR, false, SitePolicyService.AccountOrder.NAME, 0, 30);
        assertThat(creators.items())
                .extracting(SitePolicyService.AccountSummary::loginName)
                .containsExactly("creator");
        assertThat(creators.total()).isOne();
        var newest = policies.list(administrator, "", null, false, SitePolicyService.AccountOrder.NEWEST, 0, 30);
        assertThat(newest.items().getLast().accountId()).isEqualTo(administrator.accountId());
        assertThat(newest.total()).isEqualTo(3);

        var all = policies.spaces(administrator, "", SitePolicyService.SpaceState.ALL, 0, 30);
        assertThat(all.total()).isEqualTo(2);
        var publicSpaces = policies.spaces(administrator, "seco", SitePolicyService.SpaceState.PUBLIC, 0, 30);
        assertThat(publicSpaces.items()).singleElement().satisfies(space -> {
            assertThat(space.workspaceId()).isEqualTo(second.value());
            assertThat(space.owners()).containsExactly("creator");
            assertThat(space.ownerCount()).isOne();
        });
        policies.change(administrator, creator.accountId(), SiteGroup.VIEWER, "Needs revision");
        assertThat(policies.spaces(administrator, "", SitePolicyService.SpaceState.RESTRICTED, 0, 30)
                        .items())
                .extracting(SitePolicyService.SiteSpace::workspaceId)
                .containsExactly(second.value());

        var overview = policies.overview(administrator, java.time.Instant.now().minus(java.time.Duration.ofDays(7)));
        assertThat(overview.accounts())
                .containsEntry(SiteGroup.ADMINISTRATOR, 1L)
                .containsEntry(SiteGroup.VIEWER, 2L)
                .containsEntry(SiteGroup.COMMUNITY, 0L);
        assertThat(overview.newAccounts()).isEqualTo(2);
        assertThat(overview.spaces()).isEqualTo(2);
        assertThat(overview.restrictedSpaces()).isOne();

        assertCode(() -> policies.overview(reader, java.time.Instant.now()), AuthException.Code.DENIED);
        assertCode(
                () -> policies.spaces(reader, "", SitePolicyService.SpaceState.ALL, 0, 30), AuthException.Code.DENIED);
        assertCode(
                () -> policies.list(administrator, "", null, false, SitePolicyService.AccountOrder.NAME, 0, 101),
                AuthException.Code.INVALID_INPUT);
    }

    @Test
    void suspensionEndsAccessAndWithdrawsWebsitesUntilRestored() {
        String password = UUID.randomUUID().toString();
        AuthPrincipal creator = AccountFixtures.create(auth, "suspended-creator", password);
        policies.change(administrator, creator.accountId(), SiteGroup.CREATOR, "Creator approved");
        auth.acceptInvitation(
                creator,
                auth.createInvitation(administrator, workspace, Set.of()).token());
        auth.changeMembership(administrator, workspace, creator.accountId(), MembershipRole.OWNER, true, Set.of());
        var key = auth.createApiKey(creator, workspace, creator.accountId(), AuthService.DEFAULT_AI_CAPABILITIES);
        WorkspaceId second = ownedSpace(creator);
        publications.requireEnabled(second);

        assertCode(
                () -> policies.suspend(administrator, administrator.accountId(), true, "Self"),
                AuthException.Code.PROTECTED_ACCOUNT);
        assertCode(
                () -> policies.suspend(creator, administrator.accountId(), true, "Revenge"), AuthException.Code.DENIED);
        assertThat(policies.suspend(administrator, creator.accountId(), true, "Spam reports"))
                .isTrue();

        assertCode(
                () -> auth.authenticatePassword("suspended-creator", password), AuthException.Code.ACCOUNT_SUSPENDED);
        assertCode(() -> accounts.account(creator), AuthException.Code.DENIED);
        assertCode(() -> auth.authenticateApiKey(key.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertThat(publications.settings(second).eligible()).isFalse();
        assertThatThrownBy(() -> publications.requireEnabled(second))
                .isInstanceOf(PublicationUnavailableException.class);
        assertThat(policies.restrictions(administrator, workspace, 0, 30).items())
                .singleElement()
                .satisfies(restriction -> {
                    assertThat(restriction.suspended()).isTrue();
                    assertThat(restriction.reason()).isNull();
                });
        assertThat(policies.list(administrator, "", null, true, SitePolicyService.AccountOrder.NAME, 0, 30)
                        .items())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.accountId()).isEqualTo(creator.accountId());
                    assertThat(summary.suspended()).isTrue();
                });

        assertThat(policies.suspend(administrator, creator.accountId(), false, "Appeal accepted"))
                .isFalse();
        AuthPrincipal restored = auth.authenticatePassword("suspended-creator", password);
        assertThat(accounts.account(restored).group()).isEqualTo(SiteGroup.CREATOR);
        auth.authorize(restored, workspace, Capability.WRITE_PRIVATE);
        publications.requireEnabled(second);
        assertCode(() -> auth.authenticateApiKey(key.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertThat(policies.suspensionHistory(administrator, creator.accountId(), 0, 30)
                        .items())
                .extracting(SitePolicyService.SuspensionChange::suspended)
                .containsExactly(false, true);
    }

    @Test
    void creatorApplicationsAreDecidedOnlyByAdministratorsWithACooldownAfterDecline() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return now.get();
            }
        };
        var applications = new CreatorApplications(
                jdbc, accounts, policies, new DataSourceTransactionManager(jdbc.getDataSource()), clock);
        AuthPrincipal reader = account("applicant");

        assertThat(applications.standing(reader).latest()).isNull();
        assertCode(() -> applications.apply(reader, "  "), AuthException.Code.INVALID_INPUT);
        var first = applications.apply(reader, "I write travel notes");
        assertThat(first.status()).isEqualTo(CreatorApplications.Status.OPEN);
        assertCode(() -> applications.apply(reader, "Again"), AuthException.Code.APPLICATION_OPEN);
        assertCode(() -> applications.list(reader, CreatorApplications.Status.OPEN, 0, 30), AuthException.Code.DENIED);
        assertCode(() -> applications.decide(reader, first.id(), true, ""), AuthException.Code.DENIED);
        assertThat(applications.open(administrator)).isOne();

        assertCode(() -> applications.decide(administrator, first.id(), false, " "), AuthException.Code.INVALID_INPUT);
        var declined = applications.decide(administrator, first.id(), false, "Please publish a sample first");
        assertThat(declined.status()).isEqualTo(CreatorApplications.Status.DECLINED);
        assertThat(applications.standing(reader).availableAt())
                .isEqualTo(java.time.Instant.parse("2026-10-08T00:00:00Z"));
        assertCode(() -> applications.apply(reader, "Sample published"), AuthException.Code.APPLICATION_COOLDOWN);
        assertCode(() -> applications.decide(administrator, first.id(), true, ""), AuthException.Code.INVALID_INPUT);

        now.set(java.time.Instant.parse("2026-10-08T00:00:01Z"));
        var second = applications.apply(reader, "Sample published");
        assertThat(applications
                        .list(administrator, CreatorApplications.Status.OPEN, 0, 30)
                        .items())
                .singleElement()
                .satisfies(queued -> {
                    assertThat(queued.accountId()).isEqualTo(reader.accountId());
                    assertThat(queued.application().statement()).isEqualTo("Sample published");
                });
        var approved = applications.decide(administrator, second.id(), true, "Welcome");
        assertThat(approved.status()).isEqualTo(CreatorApplications.Status.APPROVED);
        assertThat(accounts.account(reader).group()).isEqualTo(SiteGroup.CREATOR);
        assertThat(policies.history(administrator, reader.accountId(), 0, 30).items())
                .singleElement()
                .satisfies(change -> assertThat(change.reason()).isEqualTo("Creator application approved: Welcome"));
        assertCode(() -> applications.apply(reader, "More"), AuthException.Code.ALREADY_CREATOR);
        assertThat(applications.standing(reader).availableAt()).isNull();

        // An administrator who applied earlier is never demoted by an approval.
        AuthPrincipal operator = account("operator-applicant");
        var pending = applications.apply(operator, "Operating the site");
        policies.change(administrator, operator.accountId(), SiteGroup.ADMINISTRATOR, "Operator added");
        applications.decide(administrator, pending.id(), true, "");
        assertThat(accounts.account(operator).group()).isEqualTo(SiteGroup.ADMINISTRATOR);
    }

    private AuthPrincipal account(String login) {
        return AccountFixtures.create(auth, login, UUID.randomUUID().toString());
    }

    private WorkspaceId ownedSpace(AuthPrincipal owner) {
        WorkspaceId id = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug,public_delivery) values (?,'Second','second',true)",
                id.value());
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role) values (?,?,'OWNER')",
                id.value(),
                owner.accountId());
        return id;
    }

    private static void assertCode(Runnable action, AuthException.Code code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AuthException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
