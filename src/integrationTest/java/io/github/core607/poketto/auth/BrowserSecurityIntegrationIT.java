package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poketto.security.allowed-origins=https://site.example.invalid")
@AutoConfigureMockMvc
@Import(io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration.class)
class BrowserSecurityIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    private static final String INITIALIZATION_TOKEN = secret();
    private static final String ORIGIN = "https://site.example.invalid";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    WorkspaceCatalog workspaces;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", () -> directory.toString());
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            // Synthetic repository for application startup, independent of authentication operations.
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        registry.add("poketto.test.repository-path", remote::toString);
        registry.add("poketto.auth.initialization-token", () -> INITIALIZATION_TOKEN);
    }

    @BeforeEach
    void resetIdentities() {
        jdbc.execute("truncate table auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at = null");
    }

    @Test
    void bootstrapLoginFixationProtectionCsrfAndLogoutUseRealSession() throws Exception {
        Csrf session = csrf(null);
        String password = secret();
        Map<String, String> body =
                Map.of("initializationToken", INITIALIZATION_TOKEN, "login", "owner", "password", password);
        mvc.perform(post("/api/auth/initialize")
                        .session(session.session())
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        mvc.perform(request(post("/api/auth/initialize"), session, body)).andExpect(status().isCreated());
        mvc.perform(request(post("/api/auth/initialize"), session, body)).andExpect(status().isConflict());
        mvc.perform(get("/api/auth/me").session(session.session())).andExpect(status().isUnauthorized());
        String priorId = session.session().getId();
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header("Origin", ORIGIN)
                        .param("username", "owner")
                        .param("password", password))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header("Origin", ORIGIN)
                        .header(session.header(), session.token())
                        .param("username", "owner")
                        .param("password", password))
                .andExpect(status().isNoContent());
        assertThat(session.session().getId()).isNotEqualTo(priorId);
        mvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(post("/api/admin/invitations").session(session.session()).header(session.header(), session.token()))
                .andExpect(status().isForbidden());
        Csrf loggedIn = csrf(session.session());
        mvc.perform(get("/api/auth/logout").session(loggedIn.session())).andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/logout").session(loggedIn.session())).andExpect(status().isForbidden());
        mvc.perform(request(post("/api/auth/logout"), loggedIn, null)).andExpect(status().isNoContent());
        assertThat(loggedIn.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invitationRegistrationAndMembershipSuspensionInvalidateExistingRequests() throws Exception {
        String ownerPassword = secret();
        AuthPrincipal owner = auth.initializeOwner(INITIALIZATION_TOKEN, "invite-owner", ownerPassword);
        Csrf ownerSession = login("invite-owner", ownerPassword);
        JsonNode invitation = body(mvc.perform(request(post("/api/admin/invitations"), ownerSession, null))
                .andExpect(status().isCreated())
                .andReturn());
        String token = invitation.get("token").stringValue();
        Csrf guest = csrf(null);
        String memberPassword = secret();
        JsonNode member = body(mvc.perform(request(
                        post("/api/auth/invitations/register"),
                        guest,
                        Map.of("token", token, "login", "joined-member", "password", memberPassword)))
                .andExpect(status().isCreated())
                .andReturn());
        mvc.perform(request(
                        post("/api/auth/invitations/register"),
                        guest,
                        Map.of("token", token, "login", "another-member", "password", secret())))
                .andExpect(status().isBadRequest());
        Csrf memberSession = login("joined-member", memberPassword);
        mvc.perform(get("/api/auth/me").session(memberSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));
        mvc.perform(get("/api/admin/members").session(memberSession.session())).andExpect(status().isForbidden());
        mvc.perform(request(post("/api/auth/invitations/accept"), memberSession, Map.of("token", token)))
                .andExpect(status().isOk());
        mvc.perform(request(
                        put("/api/admin/members/" + member.get("accountId").stringValue()),
                        ownerSession,
                        Map.of("role", "MEMBER", "active", false)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(memberSession.session())).andExpect(status().isForbidden());
        mvc.perform(request(
                        put("/api/admin/members/" + owner.accountId()),
                        ownerSession,
                        Map.of("role", "OWNER", "active", false)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/admin/invitations").session(ownerSession.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    }

    @Test
    void bearerMcpIsStatelessSeparateFromBrowserAndRevalidatesRevocation() throws Exception {
        String password = secret();
        AuthPrincipal owner = auth.initializeOwner(INITIALIZATION_TOKEN, "key-owner", password);
        Csrf session = login("key-owner", password);
        JsonNode key =
                body(mvc.perform(request(post("/api/admin/keys"), session, Map.of("accountId", owner.accountId())))
                        .andExpect(status().isCreated())
                        .andReturn());
        String bearer = "Bearer " + key.get("token").stringValue();
        mvc.perform(get("/api/auth/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").session(session.session())).andExpect(status().isUnauthorized());
        MvcResult accepted = mvc.perform(post("/mcp").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(accepted.getRequest().getSession(false)).isNull();
        mvc.perform(post("/mcp").header("Authorization", bearer).header("Origin", "https://unexpected.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/keys").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                key.get("token").stringValue()))));
        mvc.perform(request(delete("/api/admin/keys/" + key.get("id").stringValue()), session, null))
                .andExpect(status().isNoContent());
        mvc.perform(post("/mcp").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(session.session())).andExpect(status().isOk());
    }

    @Test
    void originsBoundsAndCredentialsFailClosedWithoutRedirectsOrSecretEchoes() throws Exception {
        Csrf session = csrf(null);
        mvc.perform(get("/api/auth/csrf").header("Origin", "null")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").header("Origin", ORIGIN, "https://unexpected.invalid"))
                .andExpect(status().isForbidden());
        String unknown = secret();
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header(session.header(), session.token())
                        .param("username", "unknown-user")
                        .param("password", unknown))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(unknown))));
        mvc.perform(post("/api/auth/initialize")
                        .session(session.session())
                        .header(session.header(), session.token())
                        .contentType("application/json")
                        .content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(request(
                        post("/api/auth/initialize"),
                        session,
                        Map.of("initializationToken", unknown, "login", "unknown-owner", "password", secret())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(unknown))));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void liveServletIssuesSecureHttpOnlyStrictCookie() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("set-cookie")).anySatisfy(cookie -> {
            assertThat(cookie).startsWith("POKETTO_SESSION=").contains("Secure", "HttpOnly", "SameSite=Strict");
        });
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
    }

    @Test
    void repeatedHttpLoginAttemptsAreThrottled() throws Exception {
        Csrf session = csrf(null);
        String password = secret();
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login")
                            .session(session.session())
                            .header(session.header(), session.token())
                            .param("username", "throttled-user")
                            .param("password", password))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header(session.header(), session.token())
                        .param("username", "throttled-user")
                        .param("password", password))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"));
        for (String variant : java.util.List.of(" throttled-user", "throttled-user ", "\tTHROTTLED-USER\t")) {
            mvc.perform(post("/api/auth/login")
                            .session(session.session())
                            .header(session.header(), session.token())
                            .param("username", variant)
                            .param("password", password))
                    .andExpect(status().isTooManyRequests());
        }
    }

    private Csrf login(String login, String password) throws Exception {
        Csrf guest = csrf(null);
        mvc.perform(post("/api/auth/login")
                        .session(guest.session())
                        .header("Origin", ORIGIN)
                        .header(guest.header(), guest.token())
                        .param("username", login)
                        .param("password", password))
                .andExpect(status().isNoContent());
        return csrf(guest.session());
    }

    private Csrf csrf(MockHttpSession existing) throws Exception {
        var request = get("/api/auth/csrf");
        if (existing != null) request.session(existing);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode response = body(result);
        return new Csrf(
                (MockHttpSession) result.getRequest().getSession(false),
                response.get("headerName").stringValue(),
                response.get("token").stringValue());
    }

    private MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder request, Csrf session, Object body) {
        request.session(session.session()).header("Origin", ORIGIN).header(session.header(), session.token());
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return request;
    }

    private JsonNode body(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private record Csrf(MockHttpSession session, String header, String token) {
        @Override
        public String toString() {
            return "Csrf[REDACTED]";
        }
    }

    private static String secret() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }
}
