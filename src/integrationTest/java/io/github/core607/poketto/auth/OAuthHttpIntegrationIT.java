package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "poketto.oauth.issuer=https://site.example",
            "poketto.security.allowed-origins=https://site.example"
        })
@AutoConfigureMockMvc
@Import(RemoteRepositoryIntegrationConfiguration.class)
class OAuthHttpIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    static final String INITIALIZE = UUID.randomUUID().toString(),
            PASSWORD = UUID.randomUUID().toString(),
            VERIFIER = "v".repeat(43);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", directory::toString);
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        registry.add("poketto.test.repository-path", remote::toString);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table auth_accounts,oauth_clients cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        auth.initializeOwner("owner", PASSWORD);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "https://site.example/mcp")
    void discoveryRegistrationLoginConsentExchangeAndDisconnectUseRealSecurityChain(String requestedResource)
            throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("https://site.example/mcp"));
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
        mvc.perform(post("/mcp"))
                .andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                        "WWW-Authenticate",
                                        "Bearer resource_metadata=\"https://site.example/.well-known/oauth-protected-resource\""));
        String id = register();
        var authorizationRequest = get("/api/auth/oauth/authorize")
                .param("client_id", id)
                .param("redirect_uri", "https://client.example/callback")
                .param("response_type", "code")
                .param("state", "browser-state")
                .param("scope", "repository:execute content:publish offline_access")
                .param("code_challenge", OAuthService.challenge(VERIFIER))
                .param("code_challenge_method", "S256");
        if (requestedResource != null) {
            authorizationRequest.param("resource", requestedResource);
        }
        MvcResult authorization = mvc.perform(authorizationRequest)
                .andExpect(status().isSeeOther())
                .andReturn();
        String request = URI.create(authorization.getResponse().getRedirectedUrl())
                .getQuery()
                .substring("request=".length());
        MockHttpSession session = (MockHttpSession) authorization.getRequest().getSession(false);
        mvc.perform(get("/api/auth/oauth/consent").session(session).param("request", request))
                .andExpect(status().isForbidden());
        JsonNode csrf = body(mvc.perform(get("/api/auth/csrf").session(session)).andReturn());
        String old = session.getId();
        mvc.perform(post("/api/auth/login")
                        .session(session)
                        .header(
                                csrf.get("headerName").asString(),
                                csrf.get("token").asString())
                        .param("username", "owner")
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());
        assertThat(session.getId()).isNotEqualTo(old);
        mvc.perform(get("/api/auth/oauth/consent").session(session).param("request", request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientName").value("Synthetic connector"));
        String decision = json.writeValueAsString(Map.of(
                "workspaceId",
                jdbc.queryForObject("select workspace_id from workspaces where is_default", UUID.class)
                        .toString(),
                "request",
                request,
                "allow",
                true,
                "scopes",
                List.of("repository:execute", "offline_access")));
        mvc.perform(post("/api/auth/oauth/consent")
                        .session(session)
                        .contentType("application/json")
                        .content(decision))
                .andExpect(status().isForbidden());
        csrf = body(mvc.perform(get("/api/auth/csrf").session(session)).andReturn());
        try (var held = jdbc.getDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (var lock = held.createStatement()) {
                lock.execute("select workspace_id from workspaces for update");
                mvc.perform(post("/api/auth/oauth/consent")
                                .session(session)
                                .header(
                                        csrf.get("headerName").asString(),
                                        csrf.get("token").asString())
                                .contentType("application/json")
                                .content(decision))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error").value("temporarily_unavailable"));
            } finally {
                held.rollback();
            }
        }
        var approved = body(mvc.perform(post("/api/auth/oauth/consent")
                        .session(session)
                        .header(
                                csrf.get("headerName").asString(),
                                csrf.get("token").asString())
                        .contentType("application/json")
                        .content(decision))
                .andExpect(status().isOk())
                .andReturn());
        String code = OAuthIntegrationIT.parameter(approved.get("redirect").asString(), "code");
        assertThat(OAuthIntegrationIT.parameter(approved.get("redirect").asString(), "state"))
                .isEqualTo("browser-state");
        var exchange = new HashMap<>(Map.of(
                "client_id", id,
                "grant_type", "authorization_code",
                "code", code,
                "code_verifier", VERIFIER,
                "redirect_uri", "https://client.example/callback"));
        for (String invalid : List.of("", "https://other.example/mcp", "https://site.example/mcp/")) {
            exchange.put("resource", invalid);
            mvc.perform(post("/api/auth/oauth/token")
                            .contentType("application/x-www-form-urlencoded")
                            .content(form(exchange)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_target"));
        }
        exchange.remove("resource");
        if (requestedResource != null) {
            exchange.put("resource", requestedResource);
        }
        JsonNode token = body(mvc.perform(post("/api/auth/oauth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content(form(exchange)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn());
        var principal = auth.authenticateApiKey(token.get("access_token").asString());
        assertThat(jdbc.queryForObject(
                        "select resource from oauth_connections where key_id=?", String.class, principal.subjectId()))
                .isEqualTo("https://site.example/mcp");
        mvc.perform(get("/api/auth/oauth/consent").session(session).param("request", request))
                .andExpect(status().isBadRequest());
        mvc.perform(get(scoped("/api/admin/connections")).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].scopes.length()").value(2));
        mvc.perform(delete(scoped("/api/admin/connections/") + principal.subjectId())
                        .session(session)
                        .header(
                                csrf.get("headerName").asString(),
                                csrf.get("token").asString()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/mcp")
                        .header(
                                "Authorization",
                                "Bearer " + token.get("access_token").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRedirectDuplicateParametersAndUnsupportedClientAuthenticationNeverRedirect() throws Exception {
        String client = register();
        for (String invalid : List.of("", "https://other.example/mcp", "https://site.example/mcp/")) {
            mvc.perform(get("/api/auth/oauth/authorize")
                            .param("client_id", client)
                            .param("redirect_uri", "https://client.example/callback")
                            .param("response_type", "code")
                            .param("state", "browser-state")
                            .param("code_challenge", OAuthService.challenge(VERIFIER))
                            .param("code_challenge_method", "S256")
                            .param("resource", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_target"))
                    .andExpect(header().doesNotExist("Location"));
        }
        mvc.perform(get("/api/auth/oauth/authorize")
                        .param("client_id", client)
                        .param("redirect_uri", "https://attacker.example/cb"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/api/auth/oauth/authorize").param("client_id", client, "another"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/auth/oauth/register")
                                .contentType("application/json")
                                .content(
                                        "{\"client_name\":\"X\",\"redirect_uris\":[\"https://client.example/cb\"],\"token_endpoint_auth_method\":\"client_secret_basic\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/oauth/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        mvc.perform(post("/api/auth/oauth/register")
                        .header("Origin", "https://attacker.example")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/oauth/register")
                        .contentType("application/json")
                        .content(" ".repeat(17000)))
                .andExpect(status().isPayloadTooLarge());
    }

    String register() throws Exception {
        return body(mvc.perform(
                                post("/api/auth/oauth/register")
                                        .contentType("application/json")
                                        .content(
                                                "{\"client_name\":\"Synthetic connector\",\"redirect_uris\":[\"https://client.example/callback\"]}"))
                        .andExpect(status().isCreated())
                        .andReturn())
                .get("client_id")
                .asString();
    }

    JsonNode body(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private String scoped(String path) {
        String workspace = jdbc.queryForObject("select workspace_id from workspaces where is_default", UUID.class)
                .toString();
        if (path.equals("/api/auth/me")) {
            return "/api/auth/workspaces/" + workspace + "/me";
        }
        return "/api/admin/workspaces/" + workspace + path.substring("/api/admin".length());
    }
}
