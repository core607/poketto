package io.github.core607.poketto.workspace.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.RegistrationInvitationPolicy;
import io.github.core607.poketto.auth.RegistrationService;
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
    private RegistrationService accounts;
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
        accounts = new RegistrationService(
                jdbc, manager, auth, RegistrationInvitationPolicy.configured(false), Clock.systemUTC());
        policies = new SitePolicyService(jdbc, accounts, manager);
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

    private AuthPrincipal account(String login) {
        return accounts.register(
                accounts.issue(administrator).token(), login, UUID.randomUUID().toString());
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
