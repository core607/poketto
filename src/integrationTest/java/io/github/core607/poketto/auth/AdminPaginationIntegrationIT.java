package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.workspace.WorkspaceCatalog;
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
class AdminPaginationIntegrationIT {
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
    void oldActiveKeyRemainsDiscoverableAndRevocableAfterOneHundredRotations() throws Exception {
        String password = secret();
        AuthPrincipal owner = auth.initializeOwner(INITIALIZATION_TOKEN, "pagination-owner", password);
        Csrf session = login("pagination-owner", password);
        JsonNode original =
                body(mvc.perform(request(post("/api/admin/keys"), session, Map.of("accountId", owner.accountId())))
                        .andExpect(status().isCreated())
                        .andReturn());
        String oldId = original.get("id").stringValue();
        jdbc.update(
                "update auth_api_keys set created_at = now() - interval '1 day' where key_id = ?",
                UUID.fromString(oldId));
        for (int index = 0; index < 100; index++) {
            JsonNode issued =
                    body(mvc.perform(request(post("/api/admin/keys"), session, Map.of("accountId", owner.accountId())))
                            .andExpect(status().isCreated())
                            .andReturn());
            mvc.perform(request(delete("/api/admin/keys/" + issued.get("id").stringValue()), session, null))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/admin/keys").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(30))
                .andExpect(jsonPath("$.total").value(101))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(30));
        mvc.perform(get("/api/admin/keys")
                        .session(session.session())
                        .param("offset", "100")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldId))
                .andExpect(jsonPath("$.items[0].revoked").value(false));
        auth.authenticateApiKey(original.get("token").stringValue());
        mvc.perform(request(delete("/api/admin/keys/" + oldId), session, null)).andExpect(status().isNoContent());
        assertThatThrownBy(() -> auth.authenticateApiKey(original.get("token").stringValue()))
                .isInstanceOf(AuthException.class);
        for (String endpoint : java.util.List.of("keys", "members", "invitations")) {
            mvc.perform(get("/api/admin/" + endpoint).session(session.session()).param("limit", "101"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/" + endpoint).session(session.session()).param("offset", "-1"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/" + endpoint)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void allMembersAndInvitationsRemainReachableBeyondTheFirstPage() throws Exception {
        String password = secret();
        AuthPrincipal owner = auth.initializeOwner(INITIALIZATION_TOKEN, "owner-pagination", password);
        Csrf session = login("owner-pagination", password);
        UUID workspace = workspaces.defaultWorkspace().id().value();
        UUID lastMember = null;
        for (int index = 0; index < 31; index++) {
            UUID member = UUID.randomUUID();
            jdbc.update(
                    "insert into auth_accounts (account_id, login_name, password_hash) values (?, ?, ?)",
                    member,
                    "member-%03d".formatted(index),
                    "fixture-no-login");
            jdbc.update(
                    "insert into auth_memberships (workspace_id, account_id, role) values (?, ?, 'MEMBER')",
                    workspace,
                    member);
            lastMember = member;
            mvc.perform(request(post("/api/admin/invitations"), session, null)).andExpect(status().isCreated());
        }
        mvc.perform(get("/api/admin/members").session(session.session()).param("offset", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(32))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].accountId").value(lastMember.toString()));
        mvc.perform(request(
                        put("/api/admin/members/" + lastMember), session, Map.of("role", "MEMBER", "active", false)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                        "select suspended_at is not null from auth_memberships where workspace_id = ? and account_id = ?",
                        Boolean.class,
                        workspace,
                        lastMember))
                .isTrue();
        JsonNode lastPage = body(mvc.perform(
                        get("/api/admin/invitations").session(session.session()).param("offset", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(31))
                .andReturn());
        String invitation = lastPage.get("items").get(0).get("id").stringValue();
        mvc.perform(request(delete("/api/admin/invitations/" + invitation), session, null))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/invitations").session(session.session()).param("offset", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].revoked").value(true));
        mvc.perform(get("/api/admin/members").session(session.session()).param("offset", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(32));
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
