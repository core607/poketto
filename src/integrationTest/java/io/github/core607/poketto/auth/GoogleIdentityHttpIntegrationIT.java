package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import({RemoteRepositoryIntegrationConfiguration.class, GoogleIdentityHttpIntegrationIT.ProviderConfiguration.class})
class GoogleIdentityHttpIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) throws Exception {
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {}
        values.add("poketto.data-dir", directory::toString);
        values.add("poketto.test.repository-path", remote::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    GoogleAccounts accounts;

    @Autowired
    ProviderFixture provider;

    private final JsonMapper json = JsonMapper.builder().build();
    private static final String PASSWORD = "synthetic-account-password";

    @BeforeEach
    void reset() {
        jdbc.execute("truncate auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        provider.identity = new GoogleAccounts.Identity("subject-one", "reader@example.test", "Reader");
        provider.exchanges = 0;
    }

    @Test
    void loginBindsTheCallbackToOneSessionAndRotatesItWithoutKeepingProviderTokens() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String prior = session.getId();
        mvc.perform(post("/api/auth/identity/google/start")
                        .session(session)
                        .contentType("application/json")
                        .content("{\"mode\":\"LOGIN\",\"returnTo\":\"/admin\"}"))
                .andExpect(status().isForbidden());
        String state = start(session, "LOGIN", "/connect?client_id=fixture&state=caller-state");
        callback(new MockHttpSession(), state)
                .andExpect(header().string("Location", "/admin?loginError=google_failed"));
        callback(session, "wrong-state").andExpect(header().string("Location", "/admin?loginError=google_failed"));
        assertThat(provider.exchanges).isZero();
        callback(session, state)
                .andExpect(header().string("Location", "/connect?client_id=fixture&state=caller-state"));
        assertThat(session.getId()).isNotEqualTo(prior);
        // The session gets the signed-in idle timeout when the login stores the account.
        assertThat(session.getMaxInactiveInterval()).isEqualTo(90 * 24 * 60 * 60);
        mvc.perform(get("/api/auth/account").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.group").value("VIEWER"));
        mvc.perform(get("/api/auth/identity/account").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordEnabled").value(false))
                .andExpect(jsonPath("$.googleEmail").value("reader@example.test"));
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isZero();
        callback(session, state).andExpect(header().string("Location", "/admin?loginError=google_failed"));
        assertThat(provider.exchanges).isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
    }

    @Test
    void sameEmailRequiresExplicitAuthenticatedLinkingAndCannotMergeAccounts() throws Exception {
        AuthPrincipal original = auth.initializeOwner("original", PASSWORD);
        jdbc.update(
                "update auth_accounts set verified_email='reader@example.test' where account_id=?",
                original.accountId());
        MockHttpSession guest = new MockHttpSession();
        callback(guest, start(guest, "LOGIN", "/admin"))
                .andExpect(header().string("Location", "/admin?loginError=google_email_in_use"));
        mvc.perform(get("/api/auth/account").session(guest)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from auth_external_identities", Integer.class))
                .isZero();
        MockHttpSession owner = login("original", PASSWORD);
        callback(owner, start(owner, "LINK", "/admin?tab=account"))
                .andExpect(header().string("Location", "/admin?tab=account"));
        AuthPrincipal google = accounts.authenticate(provider.identity);
        assertThat(google.accountId()).isEqualTo(original.accountId());
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships", Integer.class))
                .isOne();
        assertThat(auth.authenticatePassword("original", PASSWORD).accountId()).isEqualTo(google.accountId());
    }

    @Test
    void switchingTheBrowserAccountDuringConsentCannotLinkTheOriginalAccount() throws Exception {
        auth.initializeOwner("first", PASSWORD);
        AccountFixtures.create(auth, "second", PASSWORD);
        MockHttpSession session = login("first", PASSWORD);
        String state = start(session, "LINK", "/admin");
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", "second")
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());
        callback(session, state).andExpect(header().string("Location", "/admin?loginError=google_failed"));
        assertThat(provider.exchanges).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_external_identities", Integer.class))
                .isZero();
        mvc.perform(get("/api/auth/account").session(session))
                .andExpect(jsonPath("$.account.loginName").value("second"));
    }

    @Test
    void lastLoginMethodCannotBeRemovedAndUnlinkingDoesNotReassignTheOldEmail() throws Exception {
        MockHttpSession session = new MockHttpSession();
        callback(session, start(session, "LOGIN", "/admin"));
        mvc.perform(csrf(session, delete("/api/auth/identity/google")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_LOGIN_METHOD"));
        jdbc.update("update auth_accounts set password_hash=?", auth.encodePassword(PASSWORD));
        mvc.perform(csrf(session, delete("/api/auth/identity/google")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordEnabled").value(true))
                .andExpect(jsonPath("$.googleEmail").isEmpty());
        assertThatThrownBy(() -> accounts.authenticate(provider.identity))
                .isInstanceOfSatisfying(
                        AuthException.class,
                        failure -> assertThat(failure.code()).isEqualTo(AuthException.Code.EMAIL_IN_USE));
        assertThat(auth.authenticatePassword("reader@example.test", PASSWORD)).isNotNull();
    }

    @Test
    void signInReturnsReadersToThePageTheyStartedFrom() throws Exception {
        for (String destination :
                new String[] {"/", "/community?tab=bookmarks", "/s/home/read/%E9%9B%A8%E5%90%8E?collection=%2F"}) {
            MockHttpSession session = new MockHttpSession();
            String state = start(session, "LOGIN", destination);
            callback(session, state).andExpect(header().string("Location", destination));
        }
    }

    @Test
    void unsafeReturnsAndAnonymousLinkingAreRejectedAndCancellationConsumesOnlyThatFlow() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (String destination : new String[] {
            "https://evil.example",
            "//evil.example",
            "/%2f%2fevil.example",
            "/admin#fragment",
            "/api/auth/logout",
            "/API/auth/logout",
            "/mcp",
            "/s/../api/auth/logout",
            "/s/%2e%2e/api/auth/logout",
            "/\\\\evil.example",
            "admin"
        }) {
            mvc.perform(csrf(session, post("/api/auth/identity/google/start"))
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of("mode", "LOGIN", "returnTo", destination))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(csrf(session, post("/api/auth/identity/google/start"))
                        .contentType("application/json")
                        .content("{\"mode\":\"LINK\",\"returnTo\":\"/admin\"}"))
                .andExpect(status().isUnauthorized());
        String state = start(session, "LOGIN", "/admin");
        mvc.perform(get("/api/auth/identity/google/callback")
                        .session(session)
                        .param("state", state)
                        .param("error", "access_denied"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/admin?loginError=google_cancelled"));
        callback(session, state).andExpect(header().string("Location", "/admin?loginError=google_failed"));
        assertThat(provider.exchanges).isZero();
    }

    @Test
    void concurrentFirstLoginsCreateOneIdentityAndLaterEmailClaimsDoNotRewriteTheBoundMailbox() throws Exception {
        var ready = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.await();
                return accounts.authenticate(provider.identity);
            });
            var second = executor.submit(() -> {
                ready.await();
                return accounts.authenticate(provider.identity);
            });
            ready.countDown();
            UUID id = first.get(10, TimeUnit.SECONDS).accountId();
            assertThat(second.get(10, TimeUnit.SECONDS).accountId()).isEqualTo(id);
            assertThat(accounts.authenticate(
                                    new GoogleAccounts.Identity("subject-one", "new-address@example.test", "Updated"))
                            .accountId())
                    .isEqualTo(id);
            assertThat(jdbc.queryForObject(
                            "select verified_email from auth_accounts where account_id=?", String.class, id))
                    .isEqualTo("reader@example.test");
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
    }

    private String start(MockHttpSession session, String mode, String destination) throws Exception {
        mvc.perform(csrf(session, post("/api/auth/identity/google/start"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("mode", mode, "returnTo", destination))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        return provider.state;
    }

    private ResultActions callback(MockHttpSession session, String state) throws Exception {
        return mvc.perform(get("/api/auth/identity/google/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "fixture-code"))
                .andExpect(status().isSeeOther());
    }

    private MockHttpSession login(String login, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", login)
                        .param("password", password))
                .andExpect(status().isNoContent());
        return session;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpSession session, MockHttpServletRequestBuilder request)
            throws Exception {
        JsonNode token = json.readTree(mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return request.session(session)
                .header(token.path("headerName").asString(), token.path("token").asString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        ProviderFixture googleFixture() {
            return new ProviderFixture();
        }
    }

    static class ProviderFixture implements GoogleIdentityProvider {
        private GoogleAccounts.Identity identity;
        private String state;
        private int exchanges;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Authorization begin() {
            state = UUID.randomUUID().toString();
            return new Authorization(
                    "https://accounts.example.test/authorize?state=" + state, state, "nonce", "verifier");
        }

        @Override
        public GoogleAccounts.Identity exchange(Authorization authorization, String code) {
            assertThat(code).isEqualTo("fixture-code");
            exchanges++;
            return identity;
        }
    }
}
