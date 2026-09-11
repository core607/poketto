package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RegistrationIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthService auth;
    private AuthPrincipal owner;
    private RegistrationService registration;
    private final Instant now = Instant.parse("2026-09-11T00:00:00Z");

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate workspaces,auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default) values (?,'Owner space',true)",
                UUID.randomUUID());
        transactions = new DataSourceTransactionManager(source);
        var encoder = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));

        auth = new AuthService(jdbc, transactions, encoder, event -> {}, Clock.fixed(now, ZoneOffset.UTC));
        owner = auth.initializeOwner("site-owner", secret());
        registration = service(false, now);
    }

    @Test
    void defaultPolicyRequiresSiteAdministratorRatherThanWorkspaceOwner() {
        var member = registration.register(registration.issue(owner).token(), "space-owner", secret());
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role) select workspace_id,?,'OWNER' from workspaces",
                member.accountId());
        assertThat(registration.account(member).siteAdministrator()).isFalse();
        assertThat(registration.mayIssue(member)).isFalse();
        assertThatThrownBy(() -> registration.issue(member))
                .isInstanceOfSatisfying(
                        AuthException.class, error -> assertThat(error.code()).isEqualTo(AuthException.Code.DENIED));
        assertThat(registration.mayIssue(owner)).isTrue();
    }

    @Test
    void enabledPolicyAllowsNoSpaceAccountsAndDisablingDoesNotRevokeTheirExistingCodes() {
        var member = registration.register(registration.issue(owner).token(), "ordinary", secret());
        var enabled = service(true, now);
        IssuedToken invitation = enabled.issue(member);
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_memberships where account_id=?", Integer.class, member.accountId()))
                .isZero();
        assertThatThrownBy(() -> registration.issue(member)).isInstanceOf(AuthException.class);
        var registered = registration.register(invitation.token(), "new-person", secret());
        assertThat(registration.account(registered).loginName()).isEqualTo("new-person");
        assertThat(registration.account(registered).siteAdministrator()).isFalse();
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_memberships where account_id=?",
                        Integer.class,
                        registered.accountId()))
                .isZero();
    }

    @Test
    void credentialKindsCannotBeSubstitutedAndOnlyDigestsAreStored() {
        var registrationCode = registration.issue(owner);
        var workspace = new io.github.core607.poketto.workspace.WorkspaceId(
                jdbc.queryForObject("select workspace_id from workspaces", UUID.class));
        var workspaceCode = auth.createInvitation(owner, workspace);
        assertThatThrownBy(() -> registration.register(workspaceCode.token(), "person", secret()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> auth.acceptInvitation(owner, registrationCode.token()))
                .isInstanceOf(AuthException.class);
        String stored = jdbc.queryForObject(
                "select token_digest from auth_registration_invitations where invitation_id=?",
                String.class,
                registrationCode.id());
        assertThat(stored).hasSize(64).isNotEqualTo(registrationCode.token());
        assertThat(registrationCode.toString()).doesNotContain(registrationCode.token());
        assertThat(registration.invitations(owner, 0, 30).toString()).doesNotContain(registrationCode.token());
    }

    @Test
    void onlyIssuerCanListOrRevokeAndPolicyDoesNotPreventCleanup() {
        var member = registration.register(registration.issue(owner).token(), "ordinary", secret());
        var enabled = service(true, now);
        var code = enabled.issue(member);
        registration.revoke(owner, code.id());
        assertThat(registration.invitations(owner, 0, 30).items())
                .extracting(AuthService.InvitationInfo::id)
                .doesNotContain(code.id());
        assertThat(registration.invitations(member, 0, 30).items())
                .singleElement()
                .satisfies(value -> assertThat(value.revoked()).isFalse());
        registration.revoke(member, code.id());
        assertThatThrownBy(() -> registration.register(code.token(), "unavailable", secret()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void expirationAndFailedAccountCreationLeaveNoPartialAccountOrMembership() {
        var code = registration.issue(owner);
        assertThatThrownBy(() -> service(false, now.plus(Duration.ofDays(7))).register(code.token(), "late", secret()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> registration.register(code.token(), "site-owner", secret()))
                .isInstanceOf(AuthException.class);
        var account = registration.register(code.token(), "valid-person", secret());
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isOne();
        assertThatThrownBy(() -> registration.register(code.token(), "replay", secret()))
                .isInstanceOf(AuthException.class);
        assertThat(registration.account(account).loginName()).isEqualTo("valid-person");
    }

    @Test
    void concurrentRedemptionCreatesOneAccountAndOneDurableConsumption() throws Exception {
        var code = registration.issue(owner);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = List.of("first-person", "second-person").stream()
                    .map(login -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            return (Object) registration.register(code.token(), login, secret());
                        } catch (AuthException error) {
                            return error;
                        }
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results =
                    List.of(calls.get(0).get(10, TimeUnit.SECONDS), calls.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(AuthPrincipal.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(AuthException.class::isInstance)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_registration_invitations where used_by is not null", Integer.class))
                .isOne();
    }

    @Test
    void policyIsAnIssuanceBoundaryRatherThanAHardcodedQuota() {
        RegistrationService denied =
                new RegistrationService(jdbc, transactions, auth, issuer -> false, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> denied.issue(owner)).isInstanceOf(AuthException.class);
        for (int index = 0; index < 11; index++) registration.issue(owner);
        assertThat(registration.invitations(owner, 0, 3).items()).hasSize(3);
        assertThat(registration.invitations(owner, 0, 3).total()).isEqualTo(11);
    }

    private RegistrationService service(boolean enabled, Instant at) {
        return new RegistrationService(
                jdbc,
                transactions,
                auth,
                RegistrationInvitationPolicy.configured(enabled),
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private static String secret() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }
}
