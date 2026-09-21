package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.GitHubConnectionException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GitHubAppGrantsIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final GitHubAppRepositories.Owner OWNER = new GitHubAppRepositories.Owner(42, "octocat", "User");
    private JdbcTemplate jdbc;
    private GitHubAppGrantStore store;
    private GitHubAppGrantCipher cipher;
    private GitHubAppOAuth oauth;
    private GitHubAppRepositories repositories;
    private MutableClock clock;
    private UUID account;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate auth_accounts cascade");
        account = UUID.randomUUID();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash,site_group) values (?,'fixture','unusable','CREATOR')",
                account);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new GitHubAppGrantCipher(
                new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(key)), "Iv.fixture");
        clock = new MutableClock();
        store = new GitHubAppGrantStore(jdbc, cipher, "Iv.fixture", clock);
        oauth = mock(GitHubAppOAuth.class);
        repositories = mock(GitHubAppRepositories.class);
        when(repositories.currentUser(anyString())).thenReturn(OWNER);
    }

    @Test
    void persistsEncryptedTokensAcrossRestartAndRejectsStaleOrDifferentOwnerConsent() {
        store.authorize(account, 0, OWNER, tokens("initial", false));
        var restarted = new GitHubAppGrantStore(jdbc, cipher, "Iv.fixture", clock);
        assertThat(restarted.tokens(restarted.find(account).orElseThrow())).isEqualTo(tokens("initial", false));
        assertThatThrownBy(() -> store.authorize(account, 0, OWNER, tokens("stale", false)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThatThrownBy(() -> store.authorize(
                        account, 1, new GitHubAppRepositories.Owner(43, "other", "User"), tokens("other", false)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        GitHubAppGrantStore.Grant replacement = store.authorize(account, 1, OWNER, tokens("new", false));
        assertThat(replacement.version()).isEqualTo(2);
        assertThat(store.tokens(replacement)).isEqualTo(tokens("new", false));
        assertThat(new String(replacement.sealedTokens(), StandardCharsets.ISO_8859_1))
                .doesNotContain("ghu_new", "ghr_new");
    }

    @Test
    void concurrentRequestsHaveOneRefreshOwnerAndPersistTheReplacementPair() throws Exception {
        store.authorize(account, 0, OWNER, tokens("old", true));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseRefresh(entered, release);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> grants().verifiedAccess(account));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: BUSY");
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).token()).isEqualTo("ghu_new");
        }
        verify(oauth, times(1)).refresh("ghr_old");
        GitHubAppGrantStore.Grant saved = store.find(account).orElseThrow();
        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
        assertThat(store.tokens(saved)).isEqualTo(tokens("new", false));
        assertThat(grants().verifiedAccess(account).token()).isEqualTo("ghu_new");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateRefreshCannotOverwriteDisconnectOrNewConsent(boolean reconnect) throws Exception {
        store.authorize(account, 0, OWNER, tokens("old", true));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseRefresh(entered, release);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = callers.submit(() -> grants().verifiedAccess(account));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                if (reconnect) {
                    store.authorize(account, 1, OWNER, tokens("consent", false));
                } else {
                    store.revoke(account, 1);
                }
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(GitHubConnectionException.class)
                    .hasRootCauseMessage("GitHub App: AUTHORIZATION_CHANGED");
        }
        GitHubAppGrantStore.Grant saved = store.find(account).orElseThrow();
        assertThat(saved.version()).isEqualTo(2);
        if (reconnect) {
            assertThat(store.tokens(saved).accessToken()).isEqualTo("ghu_consent");
        } else {
            assertThat(saved.state()).isEqualTo(GitHubAppGrantStore.State.REVOKED);
            assertThat(saved.sealedTokens()).isNull();
        }
    }

    @Test
    void crashedRefreshExpiresWithoutRetryingThePossiblyConsumedToken() {
        GitHubAppGrantStore.Grant initial = store.authorize(account, 0, OWNER, tokens("old", true));
        GitHubAppGrantStore.Grant lease = store.claimRefresh(initial).orElseThrow();
        clock.now = NOW.plusSeconds(61);
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
        assertThatThrownBy(() -> store.refreshed(lease, tokens("late", false)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        GitHubAppGrantStore.Grant saved = store.find(account).orElseThrow();
        assertThat(saved.state()).isEqualTo(GitHubAppGrantStore.State.REAUTHORIZATION);
        assertThat(saved.sealedTokens()).isNull();
        verify(oauth, times(0)).refresh(anyString());
    }

    @Test
    void ambiguousRefreshFailureRequiresNewConsentRatherThanRepeatingTheOldRefreshToken() {
        store.authorize(account, 0, OWNER, tokens("old", true));
        when(oauth.refresh("ghr_old"))
                .thenThrow(new GitHubConnectionException(GitHubConnectionException.Code.UNAVAILABLE));
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: UNAVAILABLE");
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
        verify(oauth, times(1)).refresh("ghr_old");
        assertThat(store.find(account).orElseThrow().sealedTokens()).isNull();
    }

    @Test
    void providerAdmissionRejectionPreservesTheUnconsumedRefreshToken() {
        store.authorize(account, 0, OWNER, tokens("old", true));
        when(oauth.refresh("ghr_old"))
                .thenThrow(new GitHubConnectionException(GitHubConnectionException.Code.BUSY))
                .thenReturn(tokens("new", false));
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: BUSY");
        GitHubAppGrantStore.Grant preserved = store.find(account).orElseThrow();
        assertThat(preserved.state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
        assertThat(preserved.version()).isEqualTo(1);
        assertThat(store.tokens(preserved).refreshToken()).isEqualTo("ghr_old");
        assertThat(grants().verifiedAccess(account).token()).isEqualTo("ghu_new");
    }

    @Test
    void disconnectDuringProviderRevalidationPreventsCredentialReturn() {
        store.authorize(account, 0, OWNER, tokens("old", false));
        when(repositories.currentUser("ghu_old")).thenAnswer(call -> {
            store.revoke(account, 1);
            return OWNER;
        });
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThat(store.find(account).orElseThrow().sealedTokens()).isNull();
    }

    @Test
    void groupDowngradePreservesExistingGrantButProviderRevocationInvalidatesIt() {
        store.authorize(account, 0, OWNER, tokens("existing", false));
        jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", account);
        assertThat(grants().verifiedAccess(account).token()).isEqualTo("ghu_existing");
        when(repositories.currentUser("ghu_existing"))
                .thenThrow(new GitHubConnectionException(GitHubConnectionException.Code.UNAVAILABLE));
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: UNAVAILABLE");
        assertThat(store.find(account).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
        doThrow(new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_REQUIRED))
                .when(repositories)
                .currentUser("ghu_existing");
        assertThatThrownBy(() -> grants().verifiedAccess(account)).hasMessage("GitHub App: AUTHORIZATION_REQUIRED");
        assertThat(store.find(account).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.REVOKED);
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts where account_id=?", Integer.class, account))
                .isEqualTo(1);
    }

    private void pauseRefresh(CountDownLatch entered, CountDownLatch release) {
        when(oauth.refresh("ghr_old")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return tokens("new", false);
        });
    }

    private GitHubAppGrants grants() {
        return new GitHubAppGrants(store, oauth, repositories, clock);
    }

    private static GitHubAppOAuth.Tokens tokens(String suffix, boolean expiring) {
        return new GitHubAppOAuth.Tokens(
                "ghu_" + suffix, NOW.plusSeconds(expiring ? 10 : 28_800), "ghr_" + suffix, NOW.plusSeconds(15_897_600));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = NOW;

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
