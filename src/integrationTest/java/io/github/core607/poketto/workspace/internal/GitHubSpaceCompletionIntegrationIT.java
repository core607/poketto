package io.github.core607.poketto.workspace.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.RepositoryInitialization;
import io.github.core607.poketto.spaces.GitHubSpaceCreation;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
class GitHubSpaceCompletionIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private AuthService auth;
    private Accounts accounts;
    private AuthPrincipal actor;
    private JdbcWorkspaceCatalog workspaces;
    private MutableClock clock;
    private Provider provider;
    private Initialization initialization;
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
        workspaces = new JdbcWorkspaceCatalog(jdbc, new TransactionTemplate(transactions));
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash,site_group) values (?,'creator',?,'CREATOR')",
                UUID.randomUUID(),
                passwords.encode("fixture-password-123"));
        actor = auth.authenticatePassword("creator", "fixture-password-123");
        jdbc.update("""
                insert into content_github_grants(account_id,client_id,github_user_id,github_login,version,state,sealed_tokens,updated_at)
                values (?,'Iv.fixture',42,'octocat',1,'ACTIVE',?,current_timestamp)
                """, actor.accountId(), new byte[] {1});
        provider = new Provider();
        initialization = new Initialization();
        request = new GitHubSpaceCreation.Request(UUID.randomUUID(), "Notes", "personal-notes", 42, "notes");
        assertThat(service().create(actor, request).stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
    }

    @Test
    void verifiedBindingAndInitializationProduceOnePrivateOwnedSpaceAndReplayWithoutProviderCalls() {
        GitHubSpaceCreation.Result ready = service().resume(actor, request.requestId());
        assertThat(ready.stage()).isEqualTo(GitHubSpaceCreation.Stage.READY);
        assertThat(ready.workspaceCreated()).isTrue();
        assertThat(ready.initializationCommit()).isEqualTo("a".repeat(40));
        var workspace = new WorkspaceId(ready.workspaceId());
        assertThat(auth.authorize(actor, workspace).role()).isEqualTo(MembershipRole.OWNER);
        assertThat(jdbc.queryForObject(
                        "select public_delivery from workspaces where workspace_id=?",
                        Boolean.class,
                        workspace.value()))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select sealed_credentials is null from content_repository_bindings", Boolean.class))
                .isTrue();
        assertThat(service().resume(actor, request.requestId())).isEqualTo(ready);
        assertThat(provider.preparations).hasValue(1);
        assertThat(provider.installations).hasValue(1);
        assertThat(initialization.writes).hasValue(1);
        assertThat(provider.creates).hasValue(1);
    }

    @Test
    void missingInstallationCanBeGrantedAndResumedWithoutRecreatingTheRepository() {
        provider.beforePrepare = () -> {
            throw new GitHubConnectionException(GitHubConnectionException.Code.INSTALLATION_REQUIRED);
        };
        GitHubSpaceCreation.Result waiting = service().resume(actor, request.requestId());
        assertThat(waiting.stage()).isEqualTo(GitHubSpaceCreation.Stage.AWAITING_INSTALLATION);
        assertThat(waiting.failureCode()).isEqualTo("INSTALLATION_REQUIRED");
        assertThat(waiting.workspaceCreated()).isFalse();
        assertNoSpace();
        provider.beforePrepare = () -> {};
        assertThat(service().resume(actor, request.requestId()).stage()).isEqualTo(GitHubSpaceCreation.Stage.READY);
        assertThat(provider.creates).hasValue(1);
    }

    @Test
    void downgradeDuringInstallationVerificationPreventsAnyLocalSpaceOrBinding() {
        provider.beforePrepare = () -> group("VIEWER");
        assertThatThrownBy(() -> service().resume(actor, request.requestId())).isInstanceOf(AuthException.class);
        assertNoSpace();
        assertThat(service().status(actor, request.requestId()).stage()).isEqualTo(GitHubSpaceCreation.Stage.BLOCKED);
        group("CREATOR");
        provider.beforePrepare = () -> {};
        assertThat(service().resume(actor, request.requestId()).stage()).isEqualTo(GitHubSpaceCreation.Stage.READY);
    }

    @Test
    void expiredPreparationCannotInstallAndDuplicateBindingRollsBackTheNewOwnerAndSpace() {
        provider.beforePrepare = () -> clock.now = clock.now.plusSeconds(301);
        assertThat(service().resume(actor, request.requestId()).workspaceCreated())
                .isFalse();
        assertNoSpace();
        provider.beforePrepare = () -> {};
        provider.duplicateInsert = true;
        GitHubSpaceCreation.Result duplicate = service().resume(actor, request.requestId());
        assertThat(duplicate.failureCode()).isEqualTo("DUPLICATE");
        assertNoSpace();
        provider.duplicateInsert = false;
        assertThat(service().resume(actor, request.requestId()).stage()).isEqualTo(GitHubSpaceCreation.Stage.READY);
    }

    @Test
    void interruptedInitializationKeepsTheBindingAndAnExistingOwnerCanResumeAfterDowngrade() {
        initialization.beforeWrite = () -> {
            throw new ContentRepositoryException("fixture interruption");
        };
        GitHubSpaceCreation.Result pending = service().resume(actor, request.requestId());
        assertThat(pending.stage()).isEqualTo(GitHubSpaceCreation.Stage.INITIALIZING);
        assertThat(pending.workspaceCreated()).isTrue();
        assertThat(pending.initializationCommit()).isNull();
        group("VIEWER");
        initialization.beforeWrite = () -> {};
        GitHubSpaceCreation.Result ready = service().resume(actor, request.requestId());
        assertThat(ready.stage()).isEqualTo(GitHubSpaceCreation.Stage.READY);
        assertThat(ready.workspaceId()).isEqualTo(pending.workspaceId());
        assertThat(provider.installations).hasValue(1);
        assertThat(provider.creates).hasValue(1);
    }

    @Test
    void expiredInitializationLeasePreventsTheWriteAndAPushWithALostResponseIsNotRepeated() {
        initialization.beforeWrite = () -> clock.now = clock.now.plusSeconds(301);
        GitHubSpaceCreation.Result expired = service().resume(actor, request.requestId());
        assertThat(expired.stage()).isEqualTo(GitHubSpaceCreation.Stage.INITIALIZING);
        assertThat(initialization.writes).hasValue(0);
        initialization.beforeWrite = () -> {};
        initialization.afterWrite = () -> {
            throw new ContentRepositoryException("fixture lost acknowledgement");
        };
        assertThat(service().resume(actor, request.requestId()).stage())
                .isEqualTo(GitHubSpaceCreation.Stage.INITIALIZING);
        initialization.afterWrite = () -> {};
        assertThat(service().resume(actor, request.requestId()).stage()).isEqualTo(GitHubSpaceCreation.Stage.READY);
        assertThat(initialization.writes).hasValue(1);
        assertThat(provider.installations).hasValue(1);
    }

    private GitHubSpaceCreation service() {
        return new GitHubSpaceCreation(jdbc, accounts, provider, clock, auth, workspaces, initialization);
    }

    private void assertNoSpace() {
        assertThat(jdbc.queryForObject("select count(*) from workspaces", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from content_repository_bindings", Integer.class))
                .isZero();
    }

    private void group(String group) {
        jdbc.update("update auth_accounts set site_group=? where account_id=?", group, actor.accountId());
    }

    private final class Provider implements GitHubRepositoryProvisioning {
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger preparations = new AtomicInteger();
        private final AtomicInteger installations = new AtomicInteger();
        private Runnable beforePrepare = () -> {};
        private boolean duplicateInsert;

        @Override
        public Owner verifiedOwner(AuthPrincipal principal) {
            return new Owner(42, "octocat", 1);
        }

        @Override
        public void requireCurrent(AuthPrincipal principal, Owner owner) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
        }

        @Override
        public Repository create(
                AuthPrincipal principal, Owner owner, String name, UUID marker, Runnable beforeCreate) {
            beforeCreate.run();
            creates.incrementAndGet();
            return new Repository(91, 42, "octocat", "notes");
        }

        @Override
        public Optional<Repository> reconcile(AuthPrincipal principal, Owner owner, String name, UUID marker) {
            throw new AssertionError("a known repository must not use its creation marker again");
        }

        @Override
        public PreparedBinding prepareBinding(
                AuthPrincipal principal, Owner owner, long repositoryId, String repositoryName) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(repositoryId).isEqualTo(91);
            assertThat(repositoryName).isEqualTo("notes");
            preparations.incrementAndGet();
            beforePrepare.run();
            return new PreparedBinding(
                    principal.accountId(),
                    owner,
                    new Repository(91, 42, "octocat", "notes"),
                    7,
                    new AccessEpochs(0, 0),
                    clock.instant(),
                    clock.instant().plusSeconds(60));
        }

        @Override
        public void install(AuthPrincipal principal, WorkspaceId workspace, PreparedBinding binding) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            installations.incrementAndGet();
            insertBinding(principal, workspace);
            if (duplicateInsert) {
                insertBinding(principal, workspace);
            }
        }

        private void insertBinding(AuthPrincipal principal, WorkspaceId workspace) {
            jdbc.update("""
                    insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,credential_kind,github_account_id,github_owner_id,github_installation_id)
                    values (?,'https://github.com/octocat/notes','github:91','GITHUB_APP',?,42,7)
                    """, workspace.value(), principal.accountId());
        }
    }

    private final class Initialization implements RepositoryInitialization {
        private final AtomicInteger writes = new AtomicInteger();
        private boolean complete;
        private Runnable beforeWrite = () -> {};
        private Runnable afterWrite = () -> {};

        @Override
        public Status status(AuthPrincipal principal, WorkspaceId workspace, Template template) {
            return new Status(!complete, complete ? List.of() : FILES);
        }

        @Override
        public Outcome apply(AuthPrincipal principal, WorkspaceId workspace, Template template) {
            throw new AssertionError("creation requires the lease guard");
        }

        @Override
        public Outcome apply(AuthPrincipal principal, WorkspaceId workspace, Template template, Runnable guard) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            if (!complete) {
                beforeWrite.run();
                auth.withAuthorization(principal, workspace, Set.of(Capability.MANAGE_KEYS), () -> {
                    guard.run();
                    writes.incrementAndGet();
                    complete = true;
                    return null;
                });
                afterWrite.run();
            }
            return new Outcome("a".repeat(40), List.of());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-21T00:00:00Z");

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
