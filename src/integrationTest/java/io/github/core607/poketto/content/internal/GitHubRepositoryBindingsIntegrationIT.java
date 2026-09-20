package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        var repositories = mock(GitHubAppRepositories.class);
        when(repositories.currentUser(anyString())).thenReturn(OWNER);
        installations = mock(GitHubAppInstallations.class);
        when(installations.issue(7, 42, 91)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return token("notes");
        });
        var github = new ManagedGitHubConnections(
                mock(Accounts.class), store, mock(GitHubAppOAuth.class), repositories, null, clock, installations);
        bindings = new GitHubRepositoryBindings(jdbc, github);
        jdbc.update("""
                insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,credential_kind,
                    github_account_id,github_owner_id,github_installation_id)
                values (?,'https://github.com/octocat/notes','github:91','GITHUB_APP',?,42,7)
                """, workspace.value(), account);
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
                .hasRootCauseMessage("GitHub App: UNAVAILABLE");
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
