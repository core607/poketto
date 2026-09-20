package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.GitHubConnections;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import({RemoteRepositoryIntegrationConfiguration.class, GitHubConnectionsHttpIntegrationIT.ProviderConfiguration.class
})
class GitHubConnectionsHttpIntegrationIT {
    private static final String ROOT = "/api/auth/workspaces/github";
    private static final String PASSWORD = "fixture-account-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {}
        registry.add("poketto.data-dir", directory::toString);
        registry.add("poketto.test.repository-path", remote::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    ProviderFixture provider;

    private final JsonMapper json = JsonMapper.builder().build();
    private AuthPrincipal owner;

    @BeforeEach
    void setup() {
        jdbc.execute("truncate auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        owner = auth.initializeOwner("owner", PASSWORD);
        provider.resetProvider();
    }

    @Test
    void authenticatesStartRequiresCsrfAndConsumesOnlyTheMatchingBrowserFlow() throws Exception {
        mvc.perform(csrf(new MockHttpSession(), post(ROOT + "/start"))).andExpect(status().isUnauthorized());
        MockHttpSession session = login("owner");
        mvc.perform(post(ROOT + "/start").session(session)).andExpect(status().isForbidden());
        String state = start(session);
        callback(new MockHttpSession(), state)
                .andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        callback(session, "wrong-state").andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isZero();
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=connected"));
        String response = mvc.perform(get(ROOT).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONNECTED"))
                .andExpect(jsonPath("$.githubUserId").value(42))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("ghu_", "ghr_", "verifier", "password");
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_external_identities", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
    }

    @Test
    void aNewConsentAttemptSupersedesTheOldBrowserFlowWithoutConsumingTheNewOne() throws Exception {
        MockHttpSession session = login("owner");
        String oldState = start(session);
        String newState = start(session);
        callback(session, oldState).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        callback(session, newState).andExpect(header().string("Location", "/admin?tab=account&github=connected"));
        assertThat(provider.exchanges).isOne();
    }

    @Test
    void credentialResetDuringConsentPreventsPersistence() throws Exception {
        MockHttpSession session = login("owner");
        String state = start(session);
        provider.duringExchange = () -> jdbc.update(
                "update auth_accounts set credential_version=credential_version+1 where account_id=?",
                owner.accountId());
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isOne();
        assertThat(jdbc.queryForObject("select count(*) from content_github_grants", Integer.class))
                .isZero();
    }

    @Test
    void viewersCannotStartNewConnectionsButCanRestoreAnExistingGrantWithoutGainingCreationEligibility()
            throws Exception {
        AccountFixtures.create(auth, "reader", PASSWORD);
        MockHttpSession reader = login("reader");
        mvc.perform(csrf(reader, post(ROOT + "/start"))).andExpect(status().isForbidden());
        MockHttpSession session = login("owner");
        callback(session, start(session)).andExpect(header().string("Location", "/admin?tab=account&github=connected"));
        jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", owner.accountId());
        callback(session, start(session)).andExpect(header().string("Location", "/admin?tab=account&github=connected"));
        mvc.perform(get(ROOT).session(session))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.eligibleToCreate").value(false));
        mvc.perform(get("/api/auth/workspaces/creation-policy").session(session))
                .andExpect(jsonPath("$.eligible").value(false));
    }

    @Test
    void switchingAccountsDuringConsentCannotConnectEitherAccount() throws Exception {
        AccountFixtures.create(auth, "other", PASSWORD);
        MockHttpSession session = login("owner");
        String state = start(session);
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", "other")
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isZero();
        assertThat(jdbc.queryForObject("select count(*) from content_github_grants", Integer.class))
                .isZero();
    }

    @Test
    void downgradeDuringProviderExchangePreventsAnInitialGrant() throws Exception {
        MockHttpSession session = login("owner");
        String state = start(session);
        provider.duringExchange =
                () -> jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", owner.accountId());
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isOne();
        assertThat(jdbc.queryForObject("select count(*) from content_github_grants", Integer.class))
                .isZero();
    }

    @Test
    void logoutDuringProviderExchangePreventsSavingReturnedTokens() throws Exception {
        MockHttpSession session = login("owner");
        String state = start(session);
        provider.duringExchange = session::invalidate;
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isOne();
        assertThat(jdbc.queryForObject("select count(*) from content_github_grants", Integer.class))
                .isZero();
    }

    @Test
    void cancellationAndExpiryNeverExchangeCredentials() throws Exception {
        MockHttpSession session = login("owner");
        String state = start(session);
        mvc.perform(get(ROOT + "/callback")
                        .session(session)
                        .param("state", state)
                        .param("error", "access_denied"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin?tab=account&github=cancelled"));
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        provider.flowTime = ProviderFixture.NOW.minusSeconds(600);
        callback(session, start(session)).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isZero();
    }

    @Test
    void disconnectDuringInitialConsentPreventsTheFirstGrantFromAppearingLater() throws Exception {
        MockHttpSession session = login("owner");
        String state = start(session);
        MockHttpServletRequestBuilder disconnect = csrf(session, delete(ROOT)).param("version", "0");
        provider.duringExchange = () -> {
            try {
                mvc.perform(disconnect)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("NOT_CONNECTED"));
            } catch (Exception failure) {
                throw new AssertionError("Concurrent disconnect failed", failure);
            }
        };
        callback(session, state).andExpect(header().string("Location", "/admin?tab=account&github=failed"));
        assertThat(provider.exchanges).isOne();
        assertThat(jdbc.queryForObject("select count(*) from content_github_grants", Integer.class))
                .isZero();
    }

    @Test
    void staleDisconnectCannotRemoveNewConsentAndDisconnectKeepsMemberships() throws Exception {
        MockHttpSession session = login("owner");
        callback(session, start(session));
        callback(session, start(session));
        mvc.perform(csrf(session, delete(ROOT)).param("version", "1")).andExpect(status().isConflict());
        mvc.perform(get(ROOT).session(session)).andExpect(jsonPath("$.state").value("CONNECTED"));
        mvc.perform(delete(ROOT).session(session).param("version", "2")).andExpect(status().isForbidden());
        mvc.perform(csrf(session, delete(ROOT)).param("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DISCONNECTED"));
        assertThat(jdbc.queryForObject("select sealed_tokens is null from content_github_grants", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
    }

    private String start(MockHttpSession session) throws Exception {
        String body = mvc.perform(csrf(session, post(ROOT + "/start")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json.readTree(body).size()).isOne();
        assertThat(body).doesNotContain(provider.verifier, "ghu_", "ghr_");
        return provider.state;
    }

    private ResultActions callback(MockHttpSession session, String state) throws Exception {
        return mvc.perform(get(ROOT + "/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "fixture-code"))
                .andExpect(status().isSeeOther());
    }

    private MockHttpSession login(String name) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", name)
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());
        return session;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpSession session, MockHttpServletRequestBuilder request)
            throws Exception {
        JsonNode token = json.readTree(mvc.perform(get("/api/auth/csrf").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString());
        return request.session(session)
                .header(token.path("headerName").asString(), token.path("token").asString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        ProviderFixture githubFixture(Accounts accounts, JdbcTemplate jdbc) {
            return new ProviderFixture(accounts, jdbc);
        }

        @Bean
        @Primary
        GitHubConnections fixtureGitHubConnections(ProviderFixture fixture) {
            return fixture.connections;
        }
    }

    static final class ProviderFixture {
        private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
        private final GitHubAppOAuth oauth = mock(GitHubAppOAuth.class);
        private final GitHubAppRepositories repositories = mock(GitHubAppRepositories.class);
        private final GitHubConnections connections;
        private Instant flowTime;
        private String state;
        private String verifier;
        private int exchanges;
        private Runnable duringExchange;

        ProviderFixture(Accounts accounts, JdbcTemplate jdbc) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            var cipher = new GitHubAppGrantCipher(
                    new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(key)), "Iv.fixture");
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var store = new GitHubAppGrantStore(jdbc, cipher, "Iv.fixture", clock);
            connections = new ManagedGitHubConnections(accounts, store, oauth, repositories, null, clock);
        }

        void resetProvider() {
            reset(oauth, repositories);
            exchanges = 0;
            flowTime = NOW;
            duringExchange = () -> {};
            when(oauth.begin()).thenAnswer(call -> {
                state = random();
                verifier = random();
                return new GitHubAppOAuth.Flow(state, verifier, flowTime);
            });
            when(oauth.authorization(any()))
                    .thenAnswer(call -> URI.create("https://github.com/login/oauth/authorize?state=" + state));
            when(oauth.exchange(any(), anyString(), anyString())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                exchanges++;
                duringExchange.run();
                return new GitHubAppOAuth.Tokens(
                        "ghu_fixture", NOW.plusSeconds(28800), "ghr_fixture", NOW.plusSeconds(15897600));
            });
            when(repositories.currentUser(anyString()))
                    .thenReturn(new GitHubAppRepositories.Owner(42, "octocat", "User"));
        }

        private String random() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
