package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.content.RepositoryCoordinates;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
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
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(properties = "poketto.security.allowed-origins=https://site.example.invalid")
@AutoConfigureMockMvc
@Import({RemoteRepositoryIntegrationConfiguration.class, SpaceCreationHttpIntegrationIT.NetworkFixture.class})
class SpaceCreationHttpIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    FixtureConnections connections;

    private final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) throws Exception {
        values.add("poketto.data-dir", directory::toString);
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {}
        values.add("poketto.test.repository-path", remote::toString);
        values.add("poketto.repository.credential-key", () -> KEY);
    }

    @Test
    void realAccountHttpEntryCreatesPrivateWorkspaceAndProtectsTokensAndForeignRotation() throws Exception {
        auth.initializeOwner("operator", "fixture-owner-password");
        var session = login("operator", "fixture-owner-password");
        String request = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of(
                "requestId",
                request,
                "displayName",
                "Private notes",
                "slug",
                "private-notes",
                "repository",
                "https://cnb.cool/example/notes",
                "username",
                "git-user",
                "token",
                "provider-fixture-secret"));
        mvc.perform(post("/api/auth/workspaces/creations")
                        .session(session)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(csrf(session, post("/api/auth/workspaces/creations"))
                        .header("Origin", "https://untrusted.invalid")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(csrf(session, post("/api/auth/workspaces/creations"))
                        .contentType("application/json")
                        .content("x".repeat(16 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(connections.verifications.get()).isZero();
        var response = mvc.perform(csrf(session, post("/api/auth/workspaces/creations"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("provider-fixture-secret", "git-user", "sealed");
        UUID workspace =
                UUID.fromString(json.readTree(response).path("workspaceId").asText());
        byte[] stored = jdbc.queryForObject(
                "select sealed_credentials from content_repository_bindings where workspace_id=?",
                byte[].class,
                workspace);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .doesNotContain("provider-fixture-secret", "git-user");
        assertThat(jdbc.queryForObject(
                        "select public_delivery from workspaces where workspace_id=?", Boolean.class, workspace))
                .isFalse();
        var cipher = new RepositoryCredentialCipher(KEY);
        assertThat(cipher.decrypt(new WorkspaceId(workspace), "https://cnb.cool/example/notes", stored)
                        .password())
                .isEqualTo("provider-fixture-secret");
        assertThatThrownBy(() -> cipher.decrypt(WorkspaceId.random(), "https://cnb.cool/example/notes", stored))
                .isInstanceOf(io.github.core607.poketto.content.ContentRepositoryException.class);
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) select ?,'outsider',password_hash from auth_accounts where login_name='operator'",
                UUID.randomUUID());
        var outsider = login("outsider", "fixture-owner-password");
        mvc.perform(get("/api/auth/workspaces/creations/" + request).session(outsider))
                .andExpect(status().isForbidden());
        mvc.perform(csrf(outsider, put("/api/auth/workspaces/" + workspace + "/repository-credentials"))
                        .contentType("application/json")
                        .content("{\"username\":\"other\",\"token\":\"replacement\"}"))
                .andExpect(status().isForbidden());
        assertThat(connections.rotations.get()).isZero();
        mvc.perform(get("/api/auth/workspaces/creation-policy")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/workspaces/creation-policy").session(outsider))
                .andExpect(status().isOk());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isNoContent());
        return session;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpSession session, MockHttpServletRequestBuilder request)
            throws Exception {
        var value = json.readTree(mvc.perform(get("/api/auth/csrf").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString());
        return request.session(session)
                .header(value.path("headerName").asText(), value.path("token").asText());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NetworkFixture {
        @Bean
        @Primary
        FixtureConnections providerFixture(JdbcTemplate jdbc) {
            return new FixtureConnections(new ManagedRepositoryConnections(
                    jdbc,
                    new RepositoryCredentialCipher(KEY),
                    new RepositoryProviderClient(),
                    new RepositoryProperties(null, null, null, null, null, null, null)));
        }
    }

    static final class FixtureConnections implements RepositoryConnections, AutoCloseable {
        final ManagedRepositoryConnections delegate;
        final AtomicInteger verifications = new AtomicInteger();
        final AtomicInteger rotations = new AtomicInteger();

        FixtureConnections(ManagedRepositoryConnections delegate) {
            this.delegate = delegate;
        }

        public boolean available() {
            return delegate.available();
        }

        public byte[] seal(WorkspaceId workspace, RepositoryCoordinates coordinates, String username, String token) {
            return delegate.seal(workspace, coordinates, username, token);
        }

        public Verified verify(WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealed) {
            verifications.incrementAndGet();
            return new Verified("cnb:123", true);
        }

        public void install(
                WorkspaceId workspace, RepositoryCoordinates coordinates, byte[] sealed, Verified verified) {
            delegate.install(workspace, coordinates, sealed, verified);
        }

        public void rotate(WorkspaceId workspace, String username, String token) {
            rotations.incrementAndGet();
        }

        public void close() {
            delegate.close();
        }
    }
}
