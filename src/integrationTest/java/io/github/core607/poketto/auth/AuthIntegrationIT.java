package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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

@Testcontainers
class AuthIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private JdbcTemplate externalJdbc;
    private DataSourceTransactionManager transactionManager;
    private AuthService auth;
    private PasswordEncoder passwords;
    private WorkspaceId workspace;
    private String initializationToken;
    private final List<AuthRevocation> events = new CopyOnWriteArrayList<>();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setup() {
        var datasource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(datasource).load().migrate();
        jdbc = new JdbcTemplate(datasource);
        externalJdbc = new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("truncate table workspaces, auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at = null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces (workspace_id, display_name, is_default) values (?, ?, true)",
                workspace.value(),
                "Test workspace");
        transactionManager = new DataSourceTransactionManager(datasource);
        passwords = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        initializationToken = secret();
        auth = service(now);
        events.clear();
    }

    @Test
    void concurrentBootstrapCreatesExactlyOneAdministratorAndPermanentlyCloses() throws Exception {
        var results = concurrent(List.of(
                () -> auth.initializeOwner(initializationToken, "first-owner", secret()),
                () -> auth.initializeOwner(initializationToken, "second-owner", secret())));
        assertThat(results.stream().filter(AuthPrincipal.class::isInstance)).hasSize(1);
        assertThat(results.stream()
                        .filter(AuthException.class::isInstance)
                        .map(AuthException.class::cast)
                        .map(AuthException::code))
                .containsExactly(AuthException.Code.ALREADY_INITIALIZED);
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts where instance_admin", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships where role = 'OWNER'", Integer.class))
                .isOne();
        AuthService restarted = service(now.plusSeconds(30));
        assertCode(
                () -> restarted.initializeOwner(initializationToken, "another-owner", secret()),
                AuthException.Code.ALREADY_INITIALIZED);
        assertCode(
                () -> auth.initializeOwner(secret(), "unknown-owner", secret()),
                AuthException.Code.INVALID_CREDENTIALS);
    }

    @Test
    void invitationCreatesAnAccountOnceWithAtomicConcurrentConsumption() throws Exception {
        AuthPrincipal owner = owner();
        IssuedToken invitation = auth.createInvitation(owner, workspace);
        var results = concurrent(List.of(
                () -> auth.registerWithInvitation(invitation.token(), "member-one", secret()),
                () -> auth.registerWithInvitation(invitation.token(), "member-two", secret())));
        assertThat(results.stream().filter(AuthPrincipal.class::isInstance)).hasSize(1);
        assertThat(results.stream()
                        .filter(AuthException.class::isInstance)
                        .map(AuthException.class::cast)
                        .map(AuthException::code))
                .containsExactly(AuthException.Code.INVALID_INVITATION);
        AuthPrincipal member = (AuthPrincipal) results.stream()
                .filter(AuthPrincipal.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(auth.authorize(member, workspace).role()).isEqualTo(MembershipRole.MEMBER);
        assertThat(auth.acceptInvitation(member, invitation.token())).isEqualTo(workspace);
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isEqualTo(2);
        assertCode(() -> auth.acceptInvitation(owner, invitation.token()), AuthException.Code.INVALID_INVITATION);
        assertThat(jdbc.queryForObject(
                        "select token_digest from auth_invitations where invitation_id = ?",
                        String.class,
                        invitation.id()))
                .hasSize(64)
                .isNotEqualTo(invitation.token());
        assertThat(invitation.toString()).doesNotContain(invitation.token());
    }

    @Test
    void invalidInvitationsShareFailureAndCannotLeaveNewAccounts() {
        AuthPrincipal owner = owner();
        IssuedToken revoked = auth.createInvitation(owner, workspace);
        auth.revokeInvitation(owner, workspace, revoked.id());
        IssuedToken expired = auth.createInvitation(owner, workspace);
        AuthService later = service(now.plus(Duration.ofDays(7)));
        assertCode(
                () -> auth.registerWithInvitation(revoked.token(), "revoked-member", secret()),
                AuthException.Code.INVALID_INVITATION);
        assertCode(
                () -> later.registerWithInvitation(expired.token(), "expired-member", secret()),
                AuthException.Code.INVALID_INVITATION);
        assertCode(
                () -> auth.registerWithInvitation(secret(), "unknown-member", secret()),
                AuthException.Code.INVALID_INVITATION);
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
        IssuedToken valid = auth.createInvitation(owner, workspace);
        assertCode(
                () -> auth.registerWithInvitation(valid.token(), "owner", secret()), AuthException.Code.INVALID_INPUT);
        assertThat(auth.registerWithInvitation(valid.token(), "new-member", secret()))
                .isNotNull();
    }

    @Test
    void membershipCannotRemoveTheLastOwnerEvenWithConcurrentOwners() throws Exception {
        AuthPrincipal owner = owner();
        AuthPrincipal second = member(owner, "second-owner");
        auth.changeMembership(owner, workspace, second.accountId(), MembershipRole.OWNER, true);
        var results = concurrent(List.of(
                () -> {
                    auth.changeMembership(owner, workspace, owner.accountId(), MembershipRole.MEMBER, true);
                    return true;
                },
                () -> {
                    auth.changeMembership(second, workspace, second.accountId(), MembershipRole.MEMBER, true);
                    return true;
                }));
        assertThat(results.stream().filter(Boolean.class::isInstance)).hasSize(1);
        assertThat(results.stream()
                        .filter(AuthException.class::isInstance)
                        .map(AuthException.class::cast)
                        .map(AuthException::code))
                .containsExactly(AuthException.Code.LAST_OWNER);
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_memberships where role = 'OWNER' and suspended_at is null",
                        Integer.class))
                .isOne();
        AuthPrincipal remaining = auth.authorize(owner, workspace).role() == MembershipRole.OWNER ? owner : second;
        assertCode(
                () -> auth.changeMembership(remaining, workspace, remaining.accountId(), MembershipRole.OWNER, false),
                AuthException.Code.LAST_OWNER);
    }

    @Test
    void keysHaveIndependentIdentityDefaultCapabilitiesAndLiveRevocation() {
        AuthPrincipal owner = owner();
        IssuedToken first = auth.createApiKey(owner, workspace, owner.accountId(), null);
        IssuedToken second = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        AuthPrincipal key = auth.authenticateApiKey(first.token());
        assertThat(key.kind()).isEqualTo(AuthPrincipal.Kind.API_KEY);
        assertThat(key.subjectId()).isEqualTo(first.id()).isNotEqualTo(owner.subjectId());
        assertThat(auth.authorize(key, workspace).capabilities()).isEqualTo(AuthService.DEFAULT_AI_CAPABILITIES);
        for (Capability denied : Set.of(Capability.PUBLISH, Capability.MANAGE_KEYS, Capability.EXECUTE_REPOSITORY)) {
            assertCode(() -> auth.authorize(key, workspace, denied), AuthException.Code.DENIED);
        }
        assertThat(jdbc.queryForObject(
                        "select token_digest from auth_api_keys where key_id = ?", String.class, first.id()))
                .hasSize(64)
                .isNotEqualTo(first.token());
        assertThat(first.toString()).doesNotContain(first.token());
        auth.revokeApiKey(owner, workspace, first.id());
        assertCode(() -> auth.authenticateApiKey(first.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertCode(() -> auth.authorize(key, workspace), AuthException.Code.DENIED);
        assertThat(auth.authenticateApiKey(second.token())).isNotNull();
        assertThat(events).contains(new AuthRevocation(workspace, Set.of(), Set.of(first.id())));
    }

    @Test
    void machineKeyManagersCannotEscalateTheirOwnCapabilitiesOrCreateInvitations() {
        AuthPrincipal owner = owner();
        IssuedToken token = auth.createApiKey(
                owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE, Capability.MANAGE_KEYS));
        AuthPrincipal manager = auth.authenticateApiKey(token.token());
        assertCode(
                () -> auth.createApiKey(manager, workspace, owner.accountId(), EnumSet.allOf(Capability.class)),
                AuthException.Code.DENIED);
        assertCode(() -> auth.createInvitation(manager, workspace), AuthException.Code.DENIED);
        assertThat(auth.createApiKey(manager, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE)))
                .isNotNull();
        AuthPrincipal member = member(owner, "member");
        assertCode(() -> auth.createApiKey(member, workspace, member.accountId(), null), AuthException.Code.DENIED);
        assertCode(
                () -> auth.createApiKey(owner, workspace, member.accountId(), Set.of(Capability.MANAGE_KEYS)),
                AuthException.Code.DENIED);
        assertThat(auth.authorize(
                        auth.authenticateApiKey(auth.createApiKey(
                                        owner, workspace, member.accountId(), Set.of(Capability.EXECUTE_REPOSITORY))
                                .token()),
                        workspace,
                        Capability.EXECUTE_REPOSITORY))
                .isNotNull();
    }

    @Test
    void promotionPreservesExplicitKeyCapabilitiesAndDemotionRevokesHeldAndCreatedKeys() {
        AuthPrincipal owner = owner();
        AuthPrincipal member = member(owner, "member");
        IssuedToken held = auth.createApiKey(owner, workspace, member.accountId(), null);

        auth.changeMembership(owner, workspace, member.accountId(), MembershipRole.OWNER, true);
        AuthPrincipal machine = auth.authenticateApiKey(held.token());
        assertThat(auth.authorize(machine, workspace).capabilities())
                .containsExactlyInAnyOrder(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE);
        assertCode(() -> auth.authorize(machine, workspace, Capability.MANAGE_KEYS), AuthException.Code.DENIED);
        assertCode(() -> auth.authorize(machine, workspace, Capability.PUBLISH), AuthException.Code.DENIED);
        assertThat(events).isEmpty();

        IssuedToken created = auth.createApiKey(member, workspace, owner.accountId(), null);
        auth.changeMembership(owner, workspace, member.accountId(), MembershipRole.MEMBER, true);
        assertCode(() -> auth.authenticateApiKey(held.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertCode(() -> auth.authenticateApiKey(created.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertThat(events)
                .containsExactly(
                        new AuthRevocation(workspace, Set.of(member.accountId()), Set.of(held.id(), created.id())));
    }

    @Test
    void suspensionRevokesHeldAndCreatedKeysOnlyInThatWorkspace() {
        AuthPrincipal owner = owner();
        AuthPrincipal member = member(owner, "member");
        auth.changeMembership(owner, workspace, member.accountId(), MembershipRole.OWNER, true);
        IssuedToken held = auth.createApiKey(owner, workspace, member.accountId(), null);
        IssuedToken created = auth.createApiKey(member, workspace, owner.accountId(), null);
        WorkspaceId other = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces (workspace_id, display_name) values (?, ?)", other.value(), "Other workspace");
        jdbc.update(
                "insert into auth_memberships (workspace_id, account_id, role) values (?, ?, 'OWNER')",
                other.value(),
                member.accountId());
        IssuedToken unrelated = auth.createApiKey(member, other, member.accountId(), null);
        AuthPrincipal heldPrincipal = auth.authenticateApiKey(held.token());
        assertCode(() -> auth.authorize(heldPrincipal, other), AuthException.Code.DENIED);
        auth.changeMembership(owner, workspace, member.accountId(), MembershipRole.OWNER, false);
        assertCode(() -> auth.authorize(member, workspace), AuthException.Code.DENIED);
        assertCode(() -> auth.authorize(heldPrincipal, workspace), AuthException.Code.DENIED);
        assertCode(() -> auth.authenticateApiKey(held.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertCode(() -> auth.authenticateApiKey(created.token()), AuthException.Code.INVALID_CREDENTIALS);
        assertThat(auth.authenticateApiKey(unrelated.token())).isNotNull();
        assertThat(auth.authorize(member, other).role()).isEqualTo(MembershipRole.OWNER);
        assertThat(events)
                .contains(new AuthRevocation(workspace, Set.of(member.accountId()), Set.of(held.id(), created.id())));
    }

    @Test
    void passwordAuthenticationUsesAdaptiveHashesAndNormalizesLogin() {
        String password = secret();
        AuthPrincipal owner = auth.initializeOwner(initializationToken, "OwNeR", password);
        assertThat(auth.authenticatePassword("OWNER", password).accountId()).isEqualTo(owner.accountId());
        String stored = jdbc.queryForObject(
                "select password_hash from auth_accounts where account_id = ?", String.class, owner.accountId());
        assertThat(stored).startsWith("{pbkdf2-v5.8}").doesNotContain(password);
        assertCode(() -> auth.authenticatePassword("owner", secret()), AuthException.Code.INVALID_CREDENTIALS);
        assertCode(() -> auth.authenticatePassword("unknown", secret()), AuthException.Code.INVALID_CREDENTIALS);
    }

    @Test
    void workspaceOperationAndRevocationSerializeAndEventsObserveCommittedState() throws Exception {
        AuthPrincipal owner = owner();
        IssuedToken token = auth.createApiKey(owner, workspace, owner.accountId(), null);
        AuthPrincipal key = auth.authenticateApiKey(token.token());
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var write = executor.submit(
                    () -> auth.withAuthorization(key, workspace, Set.of(Capability.WRITE_PRIVATE), () -> {
                        inside.countDown();
                        await(release);
                        return "committed";
                    }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = executor.submit(() -> auth.revokeApiKey(owner, workspace, token.id()));
            assertThatThrownBy(() -> revoke.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(write.get(5, TimeUnit.SECONDS)).isEqualTo("committed");
            revoke.get(5, TimeUnit.SECONDS);
            assertCode(
                    () -> auth.withAuthorization(key, workspace, Set.of(Capability.WRITE_PRIVATE), () -> "unreachable"),
                    AuthException.Code.DENIED);
        } finally {
            release.countDown();
        }
    }

    @Test
    void rolledBackRevocationDoesNotNotifyExecutionConsumers() {
        AuthPrincipal owner = owner();
        IssuedToken token = auth.createApiKey(owner, workspace, owner.accountId(), null);
        assertThatThrownBy(() -> auth.withAuthorization(owner, workspace, Set.of(Capability.MANAGE_KEYS), () -> {
                    auth.revokeApiKey(owner, workspace, token.id());
                    throw new IllegalStateException("test rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
        assertThat(auth.authenticateApiKey(token.token())).isNotNull();
    }

    @Test
    void loginUpgradesARecognizedOlderPasswordHash() {
        String password = secret();
        AuthPrincipal owner = auth.initializeOwner(initializationToken, "owner", password);
        PasswordEncoder legacy = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_5();
        jdbc.update(
                "update auth_accounts set password_hash = ? where account_id = ?",
                "{pbkdf2-legacy}" + legacy.encode(password),
                owner.accountId());
        PasswordEncoder upgradeable = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8",
                Map.of("pbkdf2-legacy", legacy, "pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        AuthService upgraded = new AuthService(
                jdbc,
                transactionManager,
                upgradeable,
                event -> {},
                Clock.fixed(now, ZoneOffset.UTC),
                initializationToken);
        assertThat(upgraded.authenticatePassword("owner", password).accountId()).isEqualTo(owner.accountId());
        assertThat(jdbc.queryForObject(
                        "select password_hash from auth_accounts where account_id = ?",
                        String.class,
                        owner.accountId()))
                .startsWith("{pbkdf2-v5.8}");
    }

    private AuthService service(Instant instant) {
        return new AuthService(
                jdbc,
                transactionManager,
                passwords,
                event -> {
                    AuthRevocation revocation = (AuthRevocation) event;
                    // A separate transaction observes durable revocation before the execution consumer is notified.
                    for (UUID id : revocation.apiKeyIds()) {
                        assertThat(externalJdbc.queryForObject(
                                        "select revoked_at is not null from auth_api_keys where key_id = ?",
                                        Boolean.class,
                                        id))
                                .isTrue();
                    }
                    events.add(revocation);
                },
                Clock.fixed(instant, ZoneOffset.UTC),
                initializationToken);
    }

    private AuthPrincipal owner() {
        return auth.initializeOwner(initializationToken, "owner", secret());
    }

    private AuthPrincipal member(AuthPrincipal owner, String login) {
        return auth.registerWithInvitation(
                auth.createInvitation(owner, workspace).token(), login, secret());
    }

    private static String secret() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    private static void assertCode(Runnable operation, AuthException.Code code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        AuthException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test coordination timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static List<Object> concurrent(List<Supplier<Object>> operations) throws Exception {
        try (var executor = Executors.newFixedThreadPool(operations.size())) {
            CountDownLatch ready = new CountDownLatch(operations.size());
            CountDownLatch go = new CountDownLatch(1);
            var futures = operations.stream()
                    .map(operation -> executor.submit(() -> {
                        ready.countDown();
                        await(go);
                        try {
                            return operation.get();
                        } catch (AuthException exception) {
                            return exception;
                        }
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        }
    }
}
