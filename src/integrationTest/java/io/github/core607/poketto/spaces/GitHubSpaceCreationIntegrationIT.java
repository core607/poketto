package io.github.core607.poketto.spaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.RepositoryInitialization;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GitHubSpaceCreationIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private Accounts accounts;
    private AuthPrincipal actor;
    private AuthService auth;
    private MutableClock clock;
    private Provider provider;
    private GitHubSpaceCreation.Request request;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate workspaces,auth_accounts cascade");
        var transactions = new DataSourceTransactionManager(source);
        clock = new MutableClock();
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        accounts = new Accounts(jdbc, transactions);
        auth = new AuthService(jdbc, transactions, passwords, event -> {}, clock);
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash,site_group) values (?,'creator',?,'CREATOR')",
                UUID.randomUUID(),
                passwords.encode("fixture-password-123"));
        actor = auth.authenticatePassword("creator", "fixture-password-123");
        provider = new Provider();
        request = new GitHubSpaceCreation.Request(UUID.randomUUID(), "Personal notes", "personal-notes", 42, "notes");
    }

    @Test
    void creationHistorySurvivesBrowserLossAndIsBoundedWithoutRequiringCreatorEligibility() {
        var requests = new HashSet<UUID>();
        for (int index = 0; index < 21; index++) {
            var item =
                    new GitHubSpaceCreation.Request(UUID.randomUUID(), "Notes " + index, "notes-" + index, 42, "notes");
            service().create(actor, item);
            requests.add(item.requestId());
        }
        jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", actor.accountId());
        GitHubSpaceCreation.History first = service().history(actor, 0);
        assertThat(first.items()).hasSize(20);
        assertThat(first.nextOffset()).isEqualTo(20);
        GitHubSpaceCreation.History second = service().history(actor, first.nextOffset());
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextOffset()).isNull();
        var recovered = new HashSet<UUID>();
        first.items().forEach(entry -> recovered.add(entry.request().requestId()));
        second.items().forEach(entry -> recovered.add(entry.request().requestId()));
        assertThat(recovered).isEqualTo(requests);
        assertThat(first.items()).allSatisfy(entry -> {
            assertThat(entry.request().githubOwnerId()).isEqualTo(42);
            assertThat(entry.result().repositoryId()).isEqualTo(91);
            assertThat(entry.result().stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        });
        assertThatThrownBy(() -> service().history(actor, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsIntentBeforeProviderMutationAndReplaysTheDurableResultAfterRestart() {
        GitHubSpaceCreation.Result result = service().create(actor, request);
        assertThat(result.stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(result.repositoryId()).isEqualTo(91L);
        assertThat(result.repository()).isEqualTo("https://github.com/octocat/notes");
        assertThat(service().create(actor, request)).isEqualTo(result);
        assertThat(service().status(actor, request.requestId())).isEqualTo(result);
        assertThat(provider.creates).hasValue(1);
        assertThat(provider.owners).hasValue(1);
        assertThat(jdbc.queryForObject("select count(*) from workspaces", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select lease_id is null from space_github_creation_attempts", Boolean.class))
                .isTrue();
    }

    @Test
    void lostResponseAndMissingRepositoryNeverCauseAnotherPost() {
        provider.afterDispatch = () -> {
            throw failure(GitHubConnectionException.Code.CREATION_UNCERTAIN);
        };
        GitHubSpaceCreation.Result first = service().create(actor, request);
        UUID marker = marker();
        assertThat(first.stage()).isEqualTo(GitHubSpaceCreation.Stage.UNCERTAIN);
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.UNCERTAIN);
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.UNCERTAIN);
        provider.recovered = Optional.of(repository());
        GitHubSpaceCreation.Result recovered = service().create(actor, request);
        assertThat(recovered.workspaceId()).isEqualTo(first.workspaceId());
        assertThat(recovered.stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(marker()).isEqualTo(marker);
        assertThat(provider.creates).hasValue(1);
        assertThat(provider.reconciliations).hasValue(3);
    }

    @Test
    void definiteRejectionIsReplayedAndCannotAdoptAnExistingRepository() {
        provider.afterDispatch = () -> {
            throw failure(GitHubConnectionException.Code.CREATION_REJECTED);
        };
        GitHubSpaceCreation.Result rejected = service().create(actor, request);
        assertThat(rejected.stage()).isEqualTo(GitHubSpaceCreation.Stage.REJECTED);
        assertThat(service().create(actor, request)).isEqualTo(rejected);
        assertThat(provider.creates).hasValue(1);
        assertThat(rejected.repositoryId()).isNull();
    }

    @Test
    void explicitInstallationDenialCanResumeTheSameRequestAfterPermissionIsRestored() {
        provider.afterDispatch = () -> {
            throw failure(GitHubConnectionException.Code.INSTALLATION_REQUIRED);
        };
        GitHubSpaceCreation.Result denied = service().create(actor, request);
        UUID marker = marker();
        assertThat(denied.stage()).isEqualTo(GitHubSpaceCreation.Stage.PREPARING);
        assertThat(denied.failureCode()).isEqualTo("INSTALLATION_REQUIRED");
        assertThat(denied.repositoryId()).isNull();
        assertThat(jdbc.queryForObject("select creation_requested from space_github_creation_attempts", Boolean.class))
                .isFalse();
        provider.afterDispatch = () -> {};
        GitHubSpaceCreation.Result resumed = service().create(actor, request);
        assertThat(resumed.stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(resumed.workspaceId()).isEqualTo(denied.workspaceId());
        assertThat(marker()).isEqualTo(marker);
        assertThat(provider.creates).hasValue(2);
        assertThat(provider.reconciliations).hasValue(0);
    }

    @Test
    void mismatchingRecoveryMarkerLeavesTheAttemptUncertain() {
        provider.afterDispatch = () -> {
            throw failure(GitHubConnectionException.Code.CREATION_UNCERTAIN);
        };
        service().create(actor, request);
        provider.beforeReconcile = () -> {
            throw failure(GitHubConnectionException.Code.REPOSITORY_CHANGED);
        };
        GitHubSpaceCreation.Result result = service().create(actor, request);
        assertThat(result.stage()).isEqualTo(GitHubSpaceCreation.Stage.UNCERTAIN);
        assertThat(result.repositoryId()).isNull();
        assertThat(result.failureCode()).isEqualTo("REPOSITORY_CHANGED");
        assertThat(provider.creates).hasValue(1);
    }

    @Test
    void downgradeBeforeDispatchPreventsCreationAndLeavesTheSameAttemptResumable() {
        provider.beforeDispatch = () -> group("VIEWER");
        assertThatThrownBy(() -> service().create(actor, request)).isInstanceOf(AuthException.class);
        UUID marker = marker();
        assertThat(provider.creates).hasValue(0);
        assertThat(service().status(actor, request.requestId()).stage()).isEqualTo(GitHubSpaceCreation.Stage.BLOCKED);
        group("CREATOR");
        provider.beforeDispatch = () -> {};
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(marker()).isEqualTo(marker);
    }

    @Test
    void downgradeAfterDispatchRecordsTheRemoteFactWithoutGrantingASpace() {
        provider.afterDispatch = () -> group("VIEWER");
        GitHubSpaceCreation.Result result = service().create(actor, request);
        assertThat(result.stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(service().status(actor, request.requestId())).isEqualTo(result);
        assertThatThrownBy(() -> service().create(actor, request)).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("select count(*) from workspaces", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isZero();
        assertThat(provider.creates).hasValue(1);
    }

    @Test
    void changedGrantBeforeDispatchPreventsCreationAndNewConsentCanResume() {
        provider.beforeDispatch = () -> provider.version++;
        GitHubSpaceCreation.Result result = service().create(actor, request);
        assertThat(result.stage()).isEqualTo(GitHubSpaceCreation.Stage.DISCONNECTED);
        assertThat(provider.creates).hasValue(0);
        assertThat(jdbc.queryForObject("select creation_requested from space_github_creation_attempts", Boolean.class))
                .isFalse();
        provider.beforeDispatch = () -> {};
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
    }

    @Test
    void duplicateConcurrentRequestsObserveOneCommittedCreationIntent() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        provider.afterDispatch = () -> {
            entered.countDown();
            await(release);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service().create(actor, request));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                GitHubSpaceCreation.Result overlapping = service().create(actor, request);
                assertThat(overlapping.stage()).isEqualTo(GitHubSpaceCreation.Stage.CREATING);
                assertThat(overlapping.retryAfterSeconds()).isPositive();
                assertThat(provider.creates).hasValue(1);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).workspaceId()).isEqualTo(overlapping.workspaceId());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void restartBeforeDispatchCanResumeButRestartAfterIntentOnlyReconciles() {
        provider.beforeDispatch = () -> {
            throw new AssertionError("simulated process loss before intent");
        };
        assertThatThrownBy(() -> service().create(actor, request)).isInstanceOf(AssertionError.class);
        UUID marker = marker();
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.PREPARING);
        clock.now = clock.now.plusSeconds(301);
        provider.beforeDispatch = () -> {};
        provider.afterDispatch = () -> {
            throw new AssertionError("simulated process loss after intent");
        };
        assertThatThrownBy(() -> service().create(actor, request)).isInstanceOf(AssertionError.class);
        clock.now = clock.now.plusSeconds(301);
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.UNCERTAIN);
        assertThat(provider.creates).hasValue(1);
        assertThat(provider.reconciliations).hasValue(1);
        assertThat(marker()).isEqualTo(marker);
    }

    @Test
    void expiredCreationLeaseIsRetryableWithoutNewConsentOrDuplicateCreation() {
        var store = new GitHubCreationStore(jdbc, clock);
        GitHubCreationStore.Attempt attempt = accounts.withAccount(
                actor,
                () -> store.claim(
                        actor.accountId(),
                        request,
                        new GitHubRepositoryProvisioning.Owner(42, "octocat", 1),
                        UUID.randomUUID()));
        clock.now = clock.now.plusSeconds(301);
        assertThatThrownBy(() -> accounts.withAccount(actor, () -> {
                    store.beginCreate(attempt);
                    return null;
                }))
                .hasMessage("GitHub App: BUSY");
        assertThatThrownBy(() -> accounts.withAccount(actor, () -> store.requireLease(attempt)))
                .hasMessage("GitHub App: BUSY");
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(provider.creates).hasValue(1);
        assertThat(provider.reconciliations).hasValue(0);
    }

    @Test
    void lateResponseCannotOverwriteTheNewLeaseRecoveryResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        provider.afterDispatch = () -> {
            entered.countDown();
            await(release);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service().create(actor, request));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = clock.now.plusSeconds(301);
                GitHubSpaceCreation.Result recovering = service().create(actor, request);
                assertThat(recovering.stage()).isEqualTo(GitHubSpaceCreation.Stage.UNCERTAIN);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(recovering);
                assertThat(service().status(actor, request.requestId()).repositoryId())
                        .isNull();
                assertThat(provider.creates).hasValue(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void admissionRejectionBeforeSendingAllowsRetryWithoutLosingTheMarker() {
        provider.afterDispatch = () -> {
            throw failure(GitHubConnectionException.Code.BUSY);
        };
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.PREPARING);
        UUID marker = marker();
        provider.afterDispatch = () -> {};
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(marker()).isEqualTo(marker);
        // The fixture counts invocations at the provider boundary; BUSY guarantees the first sent no HTTP request.
        assertThat(provider.creates).hasValue(2);
    }

    @Test
    void differentInputsAndForeignAccountsCannotReuseAnAttempt() {
        service().create(actor, request);
        var different =
                new GitHubSpaceCreation.Request(request.requestId(), request.displayName(), "other-slug", 42, "notes");
        assertThatThrownBy(() -> service().create(actor, different)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) select ?,'stranger',password_hash from auth_accounts where account_id=?",
                UUID.randomUUID(),
                actor.accountId());
        AuthPrincipal stranger = auth.authenticatePassword("stranger", "fixture-password-123");
        assertThatThrownBy(() -> service().status(stranger, request.requestId()))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service().create(stranger, request)).isInstanceOf(AuthException.class);
        assertThat(provider.creates).hasValue(1);
    }

    private GitHubSpaceCreation service() {
        return new GitHubSpaceCreation(
                jdbc,
                accounts,
                provider,
                clock,
                auth,
                mock(WorkspaceRegistry.class),
                mock(RepositoryInitialization.class));
    }

    private UUID marker() {
        return jdbc.queryForObject("select creation_marker from space_github_creation_attempts", UUID.class);
    }

    private void group(String group) {
        jdbc.update("update auth_accounts set site_group=? where account_id=?", group, actor.accountId());
    }

    private static GitHubConnectionException failure(GitHubConnectionException.Code code) {
        return new GitHubConnectionException(code);
    }

    private static GitHubRepositoryProvisioning.Repository repository() {
        return new GitHubRepositoryProvisioning.Repository(91, 42, "octocat", "notes");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted", interrupted);
        }
    }

    private final class Provider implements GitHubRepositoryProvisioning {
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger owners = new AtomicInteger();
        private final AtomicInteger reconciliations = new AtomicInteger();
        private long version = 1;
        private Runnable beforeDispatch = () -> {};
        private Runnable afterDispatch = () -> {};
        private Runnable beforeReconcile = () -> {};
        private Optional<Repository> recovered = Optional.empty();

        @Override
        public PreparedBinding prepareBinding(
                AuthPrincipal principal, Owner owner, long repositoryId, String repositoryName) {
            throw new AssertionError("remote creation does not verify installation access");
        }

        @Override
        public void install(AuthPrincipal principal, WorkspaceId workspace, PreparedBinding binding) {
            throw new AssertionError("remote creation does not install workspace bindings");
        }

        @Override
        public Owner verifiedOwner(AuthPrincipal principal) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            owners.incrementAndGet();
            return new Owner(42, "octocat", version);
        }

        @Override
        public void requireCurrent(AuthPrincipal principal, Owner owner) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            if (owner.grantVersion() != version) {
                throw failure(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
            }
        }

        @Override
        public Repository create(
                AuthPrincipal principal, Owner owner, String name, UUID marker, Runnable beforeCreate) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(jdbc.queryForObject(
                            "select count(*) from space_github_creation_attempts where account_id=? and creation_marker=?",
                            Integer.class,
                            principal.accountId(),
                            marker))
                    .isOne();
            beforeDispatch.run();
            beforeCreate.run();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(jdbc.queryForObject(
                            "select stage from space_github_creation_attempts where creation_marker=?",
                            String.class,
                            marker))
                    .isEqualTo("CREATING");
            assertThat(jdbc.queryForObject(
                            "select creation_requested from space_github_creation_attempts where creation_marker=?",
                            Boolean.class,
                            marker))
                    .isTrue();
            creates.incrementAndGet();
            afterDispatch.run();
            return repository();
        }

        @Override
        public Optional<Repository> reconcile(AuthPrincipal principal, Owner owner, String name, UUID marker) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(GitHubSpaceCreationIntegrationIT.this.marker()).isEqualTo(marker);
            reconciliations.incrementAndGet();
            beforeReconcile.run();
            return recovered;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-21T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
