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
import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.spaces.SpaceCreationService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SpaceCreationIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthService auth;
    private AuthPrincipal actor;
    private RegistrationService accounts;
    private JdbcWorkspaceCatalog catalog;
    private RepositoryFixture remote;
    private final Instant now = Instant.parse("2026-09-11T00:00:00Z");

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate workspaces,auth_accounts cascade");
        transactions = new DataSourceTransactionManager(source);
        catalog = new JdbcWorkspaceCatalog(jdbc, new TransactionTemplate(transactions));
        catalog.ensureDefaultWorkspace();
        var encoder = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, transactions, encoder, event -> {}, Clock.fixed(now, ZoneOffset.UTC));
        accounts = new RegistrationService(
                jdbc,
                transactions,
                auth,
                RegistrationInvitationPolicy.configured(false),
                Clock.fixed(now, ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) values (?,'creator',?)",
                id,
                encoder.encode("fixture-password-123"));
        actor = auth.authenticatePassword("creator", "fixture-password-123");
        remote = new RepositoryFixture();
    }

    @Test
    void creationCommitsOwnerBindingAndPrivateDefaultTogetherAndReplaysAfterRestart() {
        UUID request = UUID.randomUUID();
        var created = create(service(now), request, "first-space", "first");
        assertThat(created.stage()).isEqualTo("READY");
        WorkspaceId workspace = new WorkspaceId(UUID.fromString(created.workspaceId()));
        assertThat(auth.authorize(actor, workspace).role()).isEqualTo(MembershipRole.OWNER);
        assertThat(jdbc.queryForObject(
                        "select public_delivery from workspaces where workspace_id=?",
                        Boolean.class,
                        workspace.value()))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select sealed_credentials is null from space_creation_attempts where request_id=?",
                        Boolean.class,
                        request))
                .isTrue();
        var replay = create(service(now.plusSeconds(600)), request, "first-space", "first");
        assertThat(replay).isEqualTo(created);
        assertThat(remote.verifications.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workspaces where not is_default", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> create(service(now), request, "different-space", "first"))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) select ?,'stranger',password_hash from auth_accounts where account_id=?",
                UUID.randomUUID(),
                actor.accountId());
        var stranger = auth.authenticatePassword("stranger", "fixture-password-123");
        assertThatThrownBy(() -> service(now).status(stranger, request)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service(now)
                        .create(
                                auth.authenticateApiKey(auth.createApiKey(
                                                actor, workspace, actor.accountId(), Set.of(Capability.READ_PRIVATE))
                                        .token()),
                                UUID.randomUUID(),
                                "Name",
                                "another",
                                "https://github.com/example/notes",
                                "user",
                                "token"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void providerFailureResumesSameIdentityAndDuplicateBindingRollsBackWorkspaceAndMembership() {
        UUID request = UUID.randomUUID();
        remote.fail = true;
        var failed = create(service(now), request, "retry-space", "first");
        assertThat(failed.stage()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(catalog.findById(new WorkspaceId(UUID.fromString(failed.workspaceId()))))
                .isEmpty();
        remote.fail = false;
        var ready = service(now)
                .create(actor, request, "My notes", "retry-space", "https://github.com/example/first", null, null);
        assertThat(ready.workspaceId()).isEqualTo(failed.workspaceId());
        assertThat(ready.stage()).isEqualTo("READY");
        var duplicate = create(service(now), UUID.randomUUID(), "second-space", "second");
        assertThat(duplicate.failureCode()).isEqualTo("DUPLICATE");
        assertThat(catalog.findById(new WorkspaceId(UUID.fromString(duplicate.workspaceId()))))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_memberships where account_id=?", Integer.class, actor.accountId()))
                .isEqualTo(1);
    }

    @Test
    void overlappingRequestsDoNotRunValidationTwiceAndAbandonedLeaseCanResume() throws Exception {
        var service = service(now);
        UUID request = UUID.randomUUID();
        remote.entered = new CountDownLatch(1);
        remote.release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> create(service, request, "concurrent-space", "first"));
            assertThat(remote.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var overlapping = create(service, request, "concurrent-space", "first");
            assertThat(overlapping.stage()).isEqualTo("VALIDATING");
            assertThat(overlapping.retryAfterSeconds()).isEqualTo(300);
            assertThat(remote.verifications.get()).isEqualTo(1);
            remote.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).workspaceId()).isEqualTo(overlapping.workspaceId());
        } finally {
            remote.release.countDown();
        }
        UUID abandoned = UUID.randomUUID();
        remote.fail = true;
        var failed = create(service, abandoned, "abandoned-space", "second");
        jdbc.update("update space_creation_attempts set stage='VALIDATING' where request_id=?", abandoned);
        assertThat(service(now.plusSeconds(301)).status(actor, abandoned).retryAfterSeconds())
                .isZero();
        remote.fail = false;
        remote.identity = "github:other";
        assertThat(create(service(now.plusSeconds(301)), abandoned, "abandoned-space", "second")
                        .workspaceId())
                .isEqualTo(failed.workspaceId());
        assertThat(service(now).status(actor, abandoned).stage()).isEqualTo("READY");
    }

    private SpaceCreationService service(Instant at) {
        return new SpaceCreationService(
                jdbc, transactions, accounts, auth, catalog, remote, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void rotationWaitDoesNotHoldWorkspaceLockAndRechecksOwnerBeforeCommit() throws Exception {
        var service = service(now);
        var created = create(service, UUID.randomUUID(), "rotation-space", "first");
        WorkspaceId workspace = WorkspaceId.parse(created.workspaceId());
        UUID other = UUID.randomUUID();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) select ?,'co-owner',password_hash from auth_accounts where account_id=?",
                other,
                actor.accountId());
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role) values (?,?,'OWNER')",
                workspace.value(),
                other);
        AuthPrincipal coOwner = auth.authenticatePassword("co-owner", "fixture-password-123");
        remote.rotationEntered = new CountDownLatch(1);
        remote.rotationRelease = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var rotating = pool.submit(() -> service.rotateCredentials(actor, workspace, "cnb", "replacement"));
            assertThat(remote.rotationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var demoting = pool.submit(() -> auth.changeMembership(
                    coOwner, workspace, actor.accountId(), MembershipRole.MEMBER, true, Set.of()));
            demoting.get(3, TimeUnit.SECONDS);
            remote.rotationRelease.countDown();
            assertThatThrownBy(() -> rotating.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AuthException.class);
            assertThat(remote.appliedRotations.get()).isZero();
        } finally {
            remote.rotationRelease.countDown();
        }
    }

    private SpaceCreationService.Result create(
            SpaceCreationService service, UUID request, String slug, String repository) {
        return service.create(
                actor,
                request,
                "My notes",
                slug,
                "https://github.com/example/" + repository,
                "git-user",
                "fixture-token");
    }

    /** The network boundary is deterministic; catalog, account checks and transaction rollback use real PostgreSQL. */
    private final class RepositoryFixture implements RepositoryConnections {
        boolean fail;
        String identity = "github:123";
        AtomicInteger verifications = new AtomicInteger();
        CountDownLatch entered;
        CountDownLatch release;
        CountDownLatch rotationEntered;
        CountDownLatch rotationRelease;
        AtomicInteger appliedRotations = new AtomicInteger();

        public boolean available() {
            return true;
        }

        public byte[] seal(WorkspaceId workspace, RepositoryCoordinates coordinates, String username, String token) {
            return new byte[] {42};
        }

        public Verified verify(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] credentials) {
            verifications.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (fail) {
                throw new RepositoryConnectionException(RepositoryConnectionException.Code.PERMISSION_DENIED);
            }
            return new Verified(identity, true);
        }

        public void install(
                WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealed, Verified verified) {
            jdbc.update(
                    "insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,sealed_credentials) values (?,?,?,?)",
                    workspace.value(),
                    coordinates.canonicalUri(),
                    verified.providerIdentity(),
                    sealed);
        }

        public CredentialRotation prepareRotation(WorkspaceId workspace, String username, String token) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            rotationEntered.countDown();
            try {
                if (!rotationRelease.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fixture timeout");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return new CredentialRotation(
                    workspace, "https://github.com/example/first", identity, new byte[] {42}, new byte[] {43});
        }

        public void applyRotation(WorkspaceId workspace, CredentialRotation rotation) {
            appliedRotations.incrementAndGet();
        }
    }
}
