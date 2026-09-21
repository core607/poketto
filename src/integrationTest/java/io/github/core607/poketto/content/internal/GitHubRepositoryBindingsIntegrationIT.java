package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.GitHubRepositoryReconnections;
import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.eclipse.jgit.transport.CredentialItem;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GitHubRepositoryBindingsIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final GitHubAppRepositories.Owner OWNER = new GitHubAppRepositories.Owner(42, "octocat", "User");
    private JdbcTemplate jdbc;
    private GitHubAppGrantStore store;
    private RepositoryCredentialCipher cipher;
    private GitHubAppInstallations installations;
    private GitHubRepositoryBindings bindings;
    private ManagedGitHubConnections github;
    private ManagedRepositoryConnections manual;
    private GitHubAppRepositories repositories;
    private Accounts accounts;
    private MutableClock clock;
    private UUID account;
    private WorkspaceId workspace;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate auth_accounts,workspaces cascade");
        account = UUID.randomUUID();
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash,site_group) values (?,'owner','unusable','CREATOR')",
                account);
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug) values (?,'Fixture','fixture')",
                workspace.value());
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(key));
        clock = new MutableClock();
        store = new GitHubAppGrantStore(jdbc, new GitHubAppGrantCipher(cipher, "Iv.fixture"), "Iv.fixture", clock);
        store.authorize(
                account,
                0,
                OWNER,
                new GitHubAppOAuth.Tokens(
                        "ghu_fixture", NOW.plusSeconds(28800), "ghr_fixture", NOW.plusSeconds(15897600)));
        repositories = mock(GitHubAppRepositories.class);
        when(repositories.currentUser(anyString())).thenReturn(OWNER);
        installations = mock(GitHubAppInstallations.class);
        when(installations.issue(7, 42, 91)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return token("notes");
        });
        manual = mock(ManagedRepositoryConnections.class);
        accounts = mock(Accounts.class);
        github = new ManagedGitHubConnections(
                accounts, store, mock(GitHubAppOAuth.class), repositories, null, clock, installations, jdbc, manual);
        bindings = new GitHubRepositoryBindings(jdbc, github);
        jdbc.update("""
                insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,credential_kind,
                    github_account_id,github_owner_id,github_installation_id)
                values (?,'https://github.com/octocat/notes','github:91','GITHUB_APP',?,42,7)
                """, workspace.value(), account);
    }

    @Test
    void verifiedInstallationCommitsOnlyAReferenceToTheCurrentGrant() {
        AuthPrincipal actor = preparingActor();
        var prepared =
                github.prepareBinding(actor, new GitHubRepositoryProvisioning.Owner(42, "octocat", 1), 91, "notes");
        jdbc.update("delete from content_repository_bindings where workspace_id=?", workspace.value());
        transaction().executeWithoutResult(status -> github.install(actor, workspace, prepared));
        assertThat(jdbc.queryForObject("select credential_kind from content_repository_bindings", String.class))
                .isEqualTo("GITHUB_APP");
        assertThat(jdbc.queryForObject(
                        "select sealed_credentials is null from content_repository_bindings", Boolean.class))
                .isTrue();
        assertThat(password(bindings.binding(workspace))).isEqualTo("ghs_fixture");
        verify(manual).rejectDefaultDuplicate(any());
    }

    @Test
    void expiredOrRevokedProofCannotInstallARepositoryBinding() {
        AuthPrincipal actor = preparingActor();
        var prepared =
                github.prepareBinding(actor, new GitHubRepositoryProvisioning.Owner(42, "octocat", 1), 91, "notes");
        jdbc.update("delete from content_repository_bindings where workspace_id=?", workspace.value());
        clock.now = NOW.plusSeconds(60);
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> github.install(actor, workspace, prepared)))
                .hasMessage("GitHub App: UNAVAILABLE");
        clock.now = NOW;
        store.revoke(account, 1);
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> github.install(actor, workspace, prepared)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from content_repository_bindings", Integer.class))
                .isZero();
    }

    @Test
    void operatorRepositoryIdentityCannotBeReboundThroughAppInstallation() {
        AuthPrincipal actor = preparingActor();
        doThrow(new RepositoryConnectionException(RepositoryConnectionException.Code.DUPLICATE))
                .when(manual)
                .rejectDefaultDuplicate(any());
        assertThatThrownBy(() -> github.prepareBinding(
                        actor, new GitHubRepositoryProvisioning.Owner(42, "octocat", 1), 91, "notes"))
                .hasMessage("Repository connection: DUPLICATE");
    }

    private AuthPrincipal preparingActor() {
        AuthPrincipal actor = mock(AuthPrincipal.class);
        when(actor.accountId()).thenReturn(account);
        when(repositories.known(42, 91, "notes", "ghu_fixture"))
                .thenReturn(token("notes").repository());
        when(installations.find(any(), anyString())).thenReturn(7L);
        return actor;
    }

    @Test
    void reconnectionRestoresTheSameRepositoryAfterRenameAndInvalidatesOldCredentials() {
        AuthPrincipal actor = preparingActor();
        RepositoryBinding old = bindings.binding(workspace);
        when(installations.find(OWNER, "renamed")).thenReturn(8L);
        when(repositories.known(42, 91, "renamed", "ghu_fixture"))
                .thenReturn(token("renamed").repository());
        when(installations.issue(8, 42, 91)).thenReturn(token("renamed"));
        var prepared = bindings.prepare(actor, workspace, "renamed");
        transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared));
        RepositoryBinding restored = bindings.binding(workspace);
        assertThat(restored.location().toString()).isEqualTo("https://github.com/octocat/renamed.git");
        restored.requireCurrent();
        assertThatThrownBy(old::requireCurrent).hasRootCauseMessage("GitHub App: REPOSITORY_CHANGED");
        verify(accounts, never()).requireCreator(any());
        verify(repositories, never()).create(anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void restoringAnUnchangedInstallationStillInvalidatesOldCredentialsAndCannotReplayProof() {
        AuthPrincipal actor = preparingActor();
        RepositoryBinding old = bindings.binding(workspace);
        var prepared = bindings.prepare(actor, workspace, "notes");
        transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared));
        assertThatThrownBy(old::requireCurrent).hasRootCauseMessage("GitHub App: REPOSITORY_CHANGED");
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared)))
                .hasMessage("GitHub App: REPOSITORY_CHANGED");
        bindings.binding(workspace).requireCurrent();
    }

    @Test
    void revokedBindingsBlockAccessUntilExplicitVerifiedReconnection() {
        AuthPrincipal actor = preparingActor();
        jdbc.update("update content_repository_bindings set github_revoked=true,github_binding_version=2");
        assertThat(bindings.status(actor, workspace).revoked()).isTrue();
        assertThatThrownBy(() -> bindings.binding(workspace)).hasRootCauseMessage("GitHub App: INSTALLATION_REQUIRED");
        verifyNoInteractions(installations);
        var prepared = bindings.prepare(actor, workspace, "notes");
        transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared));
        assertThat(bindings.status(actor, workspace).revoked()).isFalse();
        bindings.binding(workspace).requireCurrent();
    }

    @Test
    void revocationDuringReconnectionRejectsTheStaleProof() {
        AuthPrincipal actor = preparingActor();
        var prepared = bindings.prepare(actor, workspace, "notes");
        jdbc.update(
                "update content_repository_bindings set github_revoked=true,github_binding_version=github_binding_version+1");
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared)))
                .hasMessage("GitHub App: REPOSITORY_CHANGED");
        assertThat(bindings.status(actor, workspace).revoked()).isTrue();
    }

    @Test
    void reconnectionRejectsExpiredProofAndChangedAccountGrant() {
        AuthPrincipal actor = preparingActor();
        var prepared = bindings.prepare(actor, workspace, "notes");
        clock.now = NOW.plusSeconds(60);
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared)))
                .hasMessage("GitHub App: UNAVAILABLE");
        clock.now = NOW;
        store.revoke(account, 1);
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, prepared)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
    }

    @Test
    void expiredReconnectionProofCanBePreparedAgainWithoutReplacingConsent() {
        AuthPrincipal actor = preparingActor();
        GitHubRepositoryReconnections.Prepared expired = bindings.prepare(actor, workspace, "notes");
        clock.now = NOW.plusSeconds(60);
        assertThatThrownBy(
                        () -> transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, expired)))
                .hasMessage("GitHub App: UNAVAILABLE");
        GitHubRepositoryReconnections.Prepared renewed = bindings.prepare(actor, workspace, "notes");
        transaction().executeWithoutResult(status -> bindings.apply(actor, workspace, renewed));
        bindings.binding(workspace).requireCurrent();
        assertThat(store.find(account).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
        assertThat(store.find(account).orElseThrow().version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select github_binding_version from content_repository_bindings", Long.class))
                .isEqualTo(2);
    }

    @Test
    void otherAccountsAndReplacementRepositoriesCannotReconnect() {
        AuthPrincipal other = mock(AuthPrincipal.class);
        when(other.accountId()).thenReturn(UUID.randomUUID());
        assertThat(bindings.status(other, workspace).authorizingAccount()).isFalse();
        assertThatThrownBy(() -> bindings.prepare(other, workspace, "notes"))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        verifyNoInteractions(installations, repositories);
        AuthPrincipal actor = preparingActor();
        when(repositories.known(42, 91, "notes", "ghu_fixture"))
                .thenThrow(new GitHubConnectionException(GitHubConnectionException.Code.REPOSITORY_CHANGED));
        assertThatThrownBy(() -> bindings.prepare(actor, workspace, "notes"))
                .hasMessage("GitHub App: REPOSITORY_CHANGED");
        verify(installations, never()).issue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void malformedRepositoryNamesCannotReachInstallationLookup() {
        AuthPrincipal actor = preparingActor();
        assertThatThrownBy(() -> bindings.prepare(actor, workspace, "../other/notes"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(installations);
    }

    @Test
    void missingInstallationIsReportedBeforeReadingUnavailableRepositoryMetadata() {
        AuthPrincipal actor = preparingActor();
        when(installations.find(any(), anyString()))
                .thenThrow(new GitHubConnectionException(GitHubConnectionException.Code.INSTALLATION_REQUIRED));
        assertThatThrownBy(() -> github.prepareBinding(
                        actor, new GitHubRepositoryProvisioning.Owner(42, "octocat", 1), 91, "notes"))
                .hasMessage("GitHub App: INSTALLATION_REQUIRED");
        verify(repositories, never()).known(42, 91, "notes", "ghu_fixture");
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void resolvesRecordedPersonalRepositoryWithoutPersistingItsInstallationToken() {
        RepositoryBinding binding = bindings.binding(workspace);
        assertThat(binding.location().toString()).isEqualTo("https://github.com/octocat/notes.git");
        assertThat(binding.managed()).isTrue();
        assertThat(password(binding)).isEqualTo("ghs_fixture");
        assertThat(jdbc.queryForObject(
                        "select sealed_credentials is null from content_repository_bindings", Boolean.class))
                .isTrue();
        assertThat(binding.toString()).doesNotContain("ghs_fixture", "ghu_fixture");
        verify(installations).issue(7, 42, 91);
    }

    @Test
    void revocationInvalidatesPreparedCredentialsAndCannotFallBackToAnotherBinding() {
        RepositoryBinding binding = bindings.binding(workspace);
        store.revoke(account, 1);
        assertThatThrownBy(binding::requireCurrent)
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseMessage("GitHub App: AUTHORIZATION_CHANGED");
        var manual = mock(ManagedRepositoryConnections.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("github", bindings);
        beans.addBean("manual", manual);
        RepositoryBindingSource source = new ContentConfiguration()
                .repositoryBindingSource(
                        properties(),
                        beans.getBeanProvider(WorkspaceCatalog.class),
                        beans.getBeanProvider(ManagedRepositoryConnections.class),
                        beans.getBeanProvider(GitHubRepositoryBindings.class));
        assertThatThrownBy(() -> source.bindingFor(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseMessage("GitHub App: AUTHORIZATION_REQUIRED");
        verifyNoInteractions(manual);
        verify(installations, times(1)).issue(7, 42, 91);
    }

    @Test
    void changedRepositoryBindingInvalidatesTheAlreadyIssuedCredential() {
        RepositoryBinding binding = bindings.binding(workspace);
        jdbc.update(
                "update content_repository_bindings set github_installation_id=8 where workspace_id=?",
                workspace.value());
        assertThatThrownBy(binding::requireCurrent)
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseMessage("GitHub App: REPOSITORY_CHANGED");
    }

    @Test
    void groupDowngradeDoesNotRevokeTheExistingRepositoryConnection() {
        jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", account);
        RepositoryBinding binding = bindings.binding(workspace);
        binding.requireCurrent();
        assertThat(password(binding)).isEqualTo("ghs_fixture");
    }

    @Test
    void disconnectDuringTokenIssuancePreventsReturningTheToken() {
        when(installations.issue(7, 42, 91)).thenAnswer(call -> {
            store.revoke(account, 1);
            return token("notes");
        });
        assertThatThrownBy(() -> bindings.binding(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseMessage("GitHub App: AUTHORIZATION_CHANGED");
    }

    @Test
    void shortLeasesExpireAndCannotSurviveClockRollback() {
        RepositoryBinding binding = bindings.binding(workspace);
        clock.now = NOW.plusSeconds(60);
        assertThatThrownBy(binding::requireCurrent)
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessage("GitHub repository access is temporarily unavailable; retry the operation")
                .hasRootCauseMessage("GitHub App: UNAVAILABLE");
        bindings.binding(workspace).requireCurrent();
        assertThat(store.find(account).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
        clock.now = NOW.minusSeconds(1);
        assertThatThrownBy(binding::requireCurrent)
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseMessage("GitHub App: UNAVAILABLE");
    }

    @Test
    void changedRepositoryCoordinatesNeedExplicitReconnection() {
        when(installations.issue(7, 42, 91)).thenReturn(token("renamed"));
        assertThatThrownBy(() -> bindings.binding(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseMessage("GitHub App: REPOSITORY_CHANGED");
    }

    @Test
    void literalProviderNamesUseTheSameCanonicalCoordinatesAsCreation() {
        var metadata = new GitHubRepositoryProvisioning.Repository(91, 42, "OctoCat", "Notes.git");
        jdbc.update(
                "update content_repository_bindings set canonical_uri=? where workspace_id=?",
                metadata.canonicalUri(),
                workspace.value());
        when(installations.issue(7, 42, 91)).thenReturn(token("Notes.git"));
        RepositoryBinding binding = bindings.binding(workspace);
        assertThat(metadata.canonicalUri()).isEqualTo("https://github.com/octocat/notes.git");
        assertThat(binding.location().toString()).isEqualTo("https://github.com/octocat/notes.git.git");
        binding.requireCurrent();
    }

    @Test
    void appBindingsCannotEnterManualTokenRotationAndLegacyBindingsRetainTheirCredentials() {
        try (var manual =
                new ManagedRepositoryConnections(jdbc, cipher, mock(RepositoryProviderClient.class), properties())) {
            assertThat(manual.binding(workspace)).isNull();
            assertThat(manual.connectionInfo(workspace))
                    .get()
                    .extracting(info -> info.tokenBased())
                    .isEqualTo(false);
            assertThatThrownBy(() -> manual.prepareRotation(workspace, "user", "token"))
                    .hasMessage("Repository connection: UNAVAILABLE");
            jdbc.update("delete from content_repository_bindings where workspace_id=?", workspace.value());
            RepositoryCoordinates coordinates = RepositoryCoordinates.parse("https://github.com/octocat/notes");
            byte[] initial = manual.seal(workspace, coordinates, "owner", "fixture-initial");
            jdbc.update(
                    "insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,sealed_credentials) values (?,?,?,?)",
                    workspace.value(),
                    coordinates.canonicalUri(),
                    "github:91",
                    initial);
            RepositoryBinding prepared = manual.binding(workspace);
            assertThat(password(prepared)).isEqualTo("fixture-initial");
            prepared.requireCurrent();
            byte[] replacement = manual.seal(workspace, coordinates, "owner", "fixture-replacement");
            jdbc.update(
                    "update content_repository_bindings set sealed_credentials=? where workspace_id=?",
                    replacement,
                    workspace.value());
            assertThatThrownBy(prepared::requireCurrent)
                    .isInstanceOf(ContentRepositoryException.class)
                    .hasMessage("Repository credentials changed; prepare a new connection");
            assertThat(password(manual.binding(workspace))).isEqualTo("fixture-replacement");
        }
    }

    private static String password(RepositoryBinding binding) {
        var password = new CredentialItem.Password();
        assertThat(binding.credentials().get(binding.location(), password)).isTrue();
        return new String(password.getValue());
    }

    private static RepositoryProperties properties() {
        return new RepositoryProperties(
                "https://github.com/operator/default.git", "operator", "fixture-token", null, null, null, null);
    }

    private static GitHubAppInstallations.Token token(String name) {
        return new GitHubAppInstallations.Token(
                "ghs_fixture",
                NOW.plusSeconds(3600),
                new GitHubAppRepositories.Repository(91, name, OWNER, true, false, false, null));
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

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
