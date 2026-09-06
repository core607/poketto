package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        // Only this loopback HTTP fixture uses an insecure session cookie.
        registry.add("server.servlet.session.cookie.secure", () -> false);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AuthService auth;

    @LocalServerPort
    int port;

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
        logicalRoutesOverHttp(password);
    }

    private void logicalRoutesOverHttp(String password) throws Exception {
        try (var client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            JsonNode anonymous = http(client, "GET", "/api/auth/csrf", null, null, 200);
            var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header(
                            anonymous.get("headerName").stringValue(),
                            anonymous.get("token").stringValue())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=editor&password=" + encode(password)))
                    .build();
            assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(204);
            JsonNode csrf = http(client, "GET", "/api/auth/csrf", null, null, 200);
            JsonNode tree = http(client, "GET", "/api/admin/repository/tree", null, null, 200);
            Map<String, String> routes = new LinkedHashMap<>();
            for (String path : new String[] {
                "中文 空格.md",
                "100%.md",
                "井#号.md",
                "literal%2Fname.md",
                "literal/name.md",
                "%E9%9B%A8.md",
                "雨.md",
                "a b.md",
                "a%20b.md"
            }) {
                routes.put(path, "/" + path.substring(0, path.length() - 3));
            }
            routes.put("目录 空格%#/index.md", "/目录 空格%#");
            routes.put("custom.md", "/explicit ?%# ");
            Map<String, String> sources = new LinkedHashMap<>();
            var changes = new ArrayList<Map<String, Object>>();
            changes.add(Map.of(
                    "path",
                    ".poketto/publishing.yaml",
                    "expectedAbsence",
                    true,
                    "content",
                    "enabled: true\nmode: public-by-default\n"));
            for (var entry : routes.entrySet()) {
                String source = entry.getKey().equals("custom.md")
                        ? "---\nroute: '/explicit ?%# '\n---\n# 原文\n"
                        : "# " + entry.getKey() + "\r\n保留原文。\r\n";
                sources.put(entry.getKey(), source);
                JsonNode preview = http(
                        client,
                        "POST",
                        "/api/admin/repository/preview",
                        csrf,
                        Map.of("path", entry.getKey(), "body", source),
                        200);
                assertThat(preview.get("body").stringValue()).contains("原文");
                changes.add(Map.of("path", entry.getKey(), "expectedAbsence", true, "content", source));
            }
            changes.add(Map.of("path", "private/隐藏 %#.md", "expectedAbsence", true, "content", "# Private"));
            JsonNode saved = http(
                    client,
                    "POST",
                    "/api/admin/repository/patch",
                    csrf,
                    Map.of("baseCommit", tree.get("commit").stringValue(), "changes", changes),
                    200);
            assertThat(saved.get("committed").booleanValue()).isTrue();
            assertThat(saved.get("snapshotUpdated").booleanValue()).isTrue();
            for (var entry : routes.entrySet()) {
                JsonNode file = http(
                        client, "GET", "/api/admin/repository/file?path=" + encode(entry.getKey()), null, null, 200);
                assertThat(file.get("source").stringValue()).isEqualTo(sources.get(entry.getKey()));
                assertThat(file.get("diagnostics").isEmpty()).isTrue();
                JsonNode document =
                        http(client, "GET", "/api/public/document?route=" + encode(entry.getValue()), null, null, 200);
                assertThat(document.get("route").stringValue()).isEqualTo(entry.getValue());
                assertThat(document.get("body").stringValue())
                        .contains(entry.getKey().equals("custom.md") ? "原文" : entry.getKey());
            }
            http(client, "GET", "/api/public/document?route=" + encode("/private/隐藏 %#"), null, null, 404);
            http(client, "GET", "/api/public/document?route=" + encode("/explicit ?%#"), null, null, 404);
        }
    }

    private JsonNode http(HttpClient client, String method, String path, JsonNode csrf, Object payload, int status)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (csrf != null)
            request.header(
                    csrf.get("headerName").stringValue(), csrf.get("token").stringValue());
        if (payload == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(status);
        return json.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
