package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
        properties = {"poketto.email.api-key=synthetic-mail-secret", "poketto.email.from=Poketto <noreply@example.test>"
        })
@AutoConfigureMockMvc
@Import({RemoteRepositoryIntegrationConfiguration.class, EmailAccountsHttpIntegrationIT.MailConfiguration.class})
class EmailAccountsHttpIntegrationIT {
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
    WorkspaceCatalog workspaces;

    @Autowired
    MailFixture mail;

    private final JsonMapper json = JsonMapper.builder().build();
    private static final String PASSWORD = "synthetic-password-before-reset";

    @BeforeEach
    void clearAccounts() {
        jdbc.execute("truncate auth_accounts,auth_email_challenges,auth_email_limits cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        mail.messages.clear();
        mail.fail = false;
    }

    @Test
    void openSignupRequiresAnEmailProofAndProducesAViewerWithoutMembership() throws Exception {
        mvc.perform(post("/api/auth/identity/signup/challenge")
                        .contentType("application/json")
                        .content("{\"email\":\"reader@example.test\"}"))
                .andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        challenge(session, "signup", " Reader@Example.test ");
        Message sent = mail.messages.getLast();
        mvc.perform(csrf(session, post("/api/auth/identity/signup"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                Map.of("proof", sent.proof(), "password", PASSWORD, "displayName", "读者"))))
                .andExpect(status().isCreated());
        AuthPrincipal account = auth.authenticatePassword("READER@EXAMPLE.TEST", PASSWORD);
        assertThat(jdbc.queryForObject(
                        "select site_group from auth_accounts where account_id=?", String.class, account.accountId()))
                .isEqualTo("VIEWER");
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_memberships where account_id=?", Integer.class, account.accountId()))
                .isZero();
        MockHttpSession signedIn = login("reader@example.test", PASSWORD);
        mvc.perform(get("/api/auth/identity/account").session(signedIn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("读者"))
                .andExpect(jsonPath("$.email").value("reader@example.test"));
        mvc.perform(get("/api/auth/site/accounts").session(signedIn)).andExpect(status().isForbidden());
        mvc.perform(csrf(session, post("/api/auth/identity/signup"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                Map.of("proof", sent.proof(), "password", PASSWORD, "displayName", "重复"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isOne();
    }

    @Test
    void legacyEmailBindingPreservesIdentityAndResetRevokesSessionsAndKeysButNotMembership() throws Exception {
        AuthPrincipal owner = auth.initializeOwner("legacy-owner", PASSWORD);
        MockHttpSession oldSession = login("legacy-owner", PASSWORD);
        var workspace = workspaces.defaultWorkspace().id();
        String key = auth.createApiKey(owner, workspace, owner.accountId(), AuthService.DEFAULT_AI_CAPABILITIES)
                .token();
        challenge(oldSession, "email", "owner@example.test");
        mvc.perform(csrf(oldSession, put("/api/auth/identity/email"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(mail.messages.getLast().proof())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.test"));
        assertThat(auth.authenticatePassword("owner@example.test", PASSWORD).accountId())
                .isEqualTo(owner.accountId());
        // The challenge-limit behavior is covered separately; this fixture starts the recovery without a wall-clock
        // wait.
        jdbc.execute("truncate auth_email_limits");
        MockHttpSession recovery = new MockHttpSession();
        challenge(recovery, "recovery", "owner@example.test");
        String reset = json.writeValueAsString(
                Map.of("proof", mail.messages.getLast().proof(), "password", "synthetic-password-after-reset"));
        mvc.perform(csrf(recovery, post("/api/auth/identity/recovery"))
                        .contentType("application/json")
                        .content(reset))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/account").session(oldSession)).andExpect(status().isUnauthorized());
        assertThat(oldSession.isInvalid()).isTrue();
        assertThatThrownBy(() -> auth.authenticatePassword("legacy-owner", PASSWORD))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> auth.authorize(owner, workspace)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> auth.authenticateApiKey(key)).isInstanceOf(AuthException.class);
        AuthPrincipal renewed = auth.authenticatePassword("legacy-owner", "synthetic-password-after-reset");
        assertThat(renewed.accountId()).isEqualTo(owner.accountId());
        assertThat(auth.authorize(renewed, workspace).role()).isEqualTo(MembershipRole.OWNER);
        mvc.perform(get("/api/auth/account").session(login("owner@example.test", "synthetic-password-after-reset")))
                .andExpect(status().isOk());
        mvc.perform(csrf(recovery, post("/api/auth/identity/recovery"))
                        .contentType("application/json")
                        .content(reset))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recoveryProofCannotFollowAnEmailToAnotherAccount() throws Exception {
        AuthPrincipal original = auth.initializeOwner("original-owner", PASSWORD);
        jdbc.update(
                "update auth_accounts set verified_email='moving@example.test' where account_id=?",
                original.accountId());
        MockHttpSession session = new MockHttpSession();
        challenge(session, "recovery", "moving@example.test");
        Message sent = mail.messages.getLast();
        jdbc.update(
                "update auth_accounts set verified_email='replacement@example.test' where account_id=?",
                original.accountId());
        UUID replacement = auth.createAccount("replacement-owner", auth.encodePassword(PASSWORD), false);
        jdbc.update("update auth_accounts set verified_email='moving@example.test' where account_id=?", replacement);
        mvc.perform(csrf(session, post("/api/auth/identity/recovery"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                Map.of("proof", sent.proof(), "password", "synthetic-new-password"))))
                .andExpect(status().isBadRequest());
        assertThat(auth.authenticatePassword("moving@example.test", PASSWORD).accountId())
                .isEqualTo(replacement);
        assertThat(auth.authenticatePassword("replacement@example.test", PASSWORD)
                        .accountId())
                .isEqualTo(original.accountId());
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts where credential_version<>0", Integer.class))
                .isZero();
    }

    @Test
    void recoveryResponseDoesNotDistinguishUnknownEmailOrProviderFailure() throws Exception {
        auth.initializeOwner("legacy-owner", PASSWORD);
        jdbc.update("update auth_accounts set verified_email='known@example.test'");
        MockHttpSession session = new MockHttpSession();
        JsonNode unknown = challenge(session, "recovery", "unknown@example.test");
        assertThat(mail.messages).isEmpty();
        mail.fail = true;
        JsonNode failed = challenge(session, "recovery", "known@example.test");
        assertThat(unknown.propertyNames()).containsExactlyInAnyOrderElementsOf(failed.propertyNames());
        assertThat(unknown.path("retryAfterSeconds").asInt())
                .isEqualTo(failed.path("retryAfterSeconds").asInt());
        assertThat(mail.messages).hasSize(1);
        mvc.perform(csrf(session, post("/api/auth/identity/recovery"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                Map.of("proof", mail.messages.getFirst().proof(), "password", PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    private JsonNode challenge(MockHttpSession session, String purpose, String email) throws Exception {
        String body = mvc.perform(csrf(session, post("/api/auth/identity/" + purpose + "/challenge"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
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
    static class MailConfiguration {
        @Bean
        @Primary
        MailFixture mailFixture() {
            return new MailFixture();
        }
    }

    static class MailFixture implements VerificationMail {
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private boolean fail;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void send(String email, String code, EmailPurpose purpose, UUID messageId) {
            messages.add(new Message(email, code, messageId));
            if (fail) {
                throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
            }
        }
    }

    private record Message(String email, String code, UUID id) {
        EmailChallenges.Proof proof() {
            return new EmailChallenges.Proof(id, email, code);
        }
    }
}
