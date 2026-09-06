package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
@SpringBootTest
@AutoConfigureMockMvc
@Import(RemoteRepositoryIntegrationConfiguration.class)
class RepositoryAdminIntegrationIT {
    @TempDir
    static Path directory;

    private static final String INITIALIZATION = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            registry.add("poketto.test.repository-path", remote::toString);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        registry.add("poketto.data-dir", directory::toString);
        registry.add("poketto.auth.initialization-token", () -> INITIALIZATION);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AuthService auth;

    @Test
    void browserEditsPreserveRawTextAndEnforceSessionCsrfAndGitPreconditions() throws Exception {
        String password = UUID.randomUUID().toString();
        auth.initializeOwner(INITIALIZATION, "editor", password);
        mvc.perform(get("/api/admin/repository/tree")).andExpect(status().isUnauthorized());
        Csrf anonymous = csrf(null);
        mvc.perform(post("/api/auth/login")
                        .session(anonymous.session())
                        .header(anonymous.header(), anonymous.token())
                        .param("username", "editor")
                        .param("password", password))
                .andExpect(status().isNoContent());
        Csrf editor = csrf(anonymous.session());
        mvc.perform(get("/api/admin/repository/file").session(editor.session()).param("path", "private/中文.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAbsence").value(true));
        String source = "\uFEFF---\r\ntags: [日常]\r\nunknown: retained\r\n---\r\n# 中文\r\n" + "原文".repeat(10_000);
        String path = "private/中文.md";
        var create = Map.of("changes", List.of(Map.of("path", path, "expectedAbsence", true, "content", source)));
        mvc.perform(post("/api/admin/repository/patch")
                        .session(editor.session())
                        .contentType("application/json")
                        .content(json.writeValueAsString(create)))
                .andExpect(status().isForbidden());
        JsonNode created = body(mvc.perform(request(editor, create))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.snapshotUpdated").value(true))
                .andReturn());
        String commit = created.get("commit").stringValue();
        String revision = created.get("revisions").get(path).stringValue();
        JsonNode file = body(mvc.perform(get("/api/admin/repository/file")
                        .session(editor.session())
                        .param("path", path))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(file.get("source").stringValue()).isEqualTo(source);
        assertThat(file.get("revision").stringValue()).isEqualTo(revision);
        mvc.perform(get("/api/admin/repository/search")
                        .session(editor.session())
                        .param("query", "原文")
                        .param("tag", "日常"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].path").value(path));
        mvc.perform(get("/api/admin/repository/search")
                        .session(editor.session())
                        .param("query", "原.*文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/public/documents").param("query", "原文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(request(editor, create)).andExpect(status().isConflict());

        var move = Map.of(
                "baseCommit",
                commit,
                "changes",
                List.of(
                        Map.of("path", path, "expectedAbsence", false, "expectedRevision", revision),
                        Map.of("path", "private/moved.md", "expectedAbsence", true, "content", source)));
        mvc.perform(request(editor, move)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/repository/file").session(editor.session()).param("path", path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAbsence").value(true));
        JsonNode history = body(mvc.perform(get("/api/admin/repository/file")
                        .session(editor.session())
                        .param("path", path)
                        .param("commit", commit))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(history.get("source").stringValue()).isEqualTo(source);
        mvc.perform(request(editor, move)).andExpect(status().isConflict());
        mvc.perform(request(editor, Map.of())).andExpect(status().isBadRequest());
        mvc.perform(request(editor, Map.of("changes", List.of(Map.of("content", "# missing path")))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/repository/search")
                        .session(editor.session())
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/repository/tree").session(editor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value("private/moved.md"));
    }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode token = body(result);
        return new Csrf(
                (MockHttpSession) result.getRequest().getSession(),
                token.get("headerName").stringValue(),
                token.get("token").stringValue());
    }

    private MockHttpServletRequestBuilder request(Csrf csrf, Object body) {
        return post("/api/admin/repository/patch")
                .session(csrf.session())
                .header(csrf.header(), csrf.token())
                .contentType("application/json")
                .content(json.writeValueAsString(body));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record Csrf(MockHttpSession session, String header, String token) {}
}
