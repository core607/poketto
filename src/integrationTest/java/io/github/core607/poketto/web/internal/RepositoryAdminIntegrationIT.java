package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(RemoteRepositoryIntegrationConfiguration.class)
class RepositoryAdminIntegrationIT {
    @TempDir
    static Path directory;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        // Windows TEMP may be an 8.3 alias; image storage requires canonical ancestors.
        directory = directory.toRealPath();
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

        // Only this loopback HTTP fixture uses an insecure session cookie.
        registry.add("server.servlet.session.cookie.secure", () -> false);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AuthService auth;

    @Autowired
    PublicContentSnapshots snapshots;

    @Autowired
    WorkspaceCatalog catalog;

    @Autowired
    JdbcTemplate jdbc;

    @LocalServerPort
    int port;

    @Test
    void browserEditsPreserveRawTextAndEnforceSessionCsrfAndGitPreconditions() throws Exception {
        String password = UUID.randomUUID().toString();
        auth.initializeOwner("editor", password);
        mvc.perform(get(scoped("/api/admin/repository/tree"))).andExpect(status().isUnauthorized());
        mvc.perform(get(scoped("/api/admin/repository/directory"))).andExpect(status().isUnauthorized());
        Csrf anonymous = csrf(null);
        mvc.perform(post("/api/auth/login")
                        .session(anonymous.session())
                        .header(anonymous.header(), anonymous.token())
                        .param("username", "editor")
                        .param("password", password))
                .andExpect(status().isNoContent());
        Csrf editor = csrf(anonymous.session());
        verifyIdentityDraft(editor);
        mvc.perform(get(scoped("/api/admin/repository/file"))
                        .session(editor.session())
                        .param("path", "private/中文.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAbsence").value(true));
        String source = "\uFEFF---\r\ntags: [日常]\r\nunknown: retained\r\n---\r\n# 中文\r\n" + "原文".repeat(10_000)
                + "\n\n[旅行可见](https://example.org/hidden-destination)";
        String path = "private/中文.md";
        var create = Map.of("changes", List.of(Map.of("path", path, "expectedAbsence", true, "content", source)));
        mvc.perform(post(scoped("/api/admin/repository/patch"))
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
        JsonNode file = body(mvc.perform(get(scoped("/api/admin/repository/file"))
                        .session(editor.session())
                        .param("path", path))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(file.get("source").stringValue()).isEqualTo(source);
        assertThat(file.get("revision").stringValue()).isEqualTo(revision);
        mvc.perform(get(scoped("/api/admin/repository/search"))
                        .session(editor.session())
                        .param("query", "原文")
                        .param("tag", "日常"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].path").value(path));
        mvc.perform(get(scoped("/api/admin/repository/search"))
                        .session(editor.session())
                        .param("query", "原.*文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(scoped("/api/admin/repository/search"))
                        .session(editor.session())
                        .param("query", "hidden-destination"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        JsonNode visibleSearch = body(mvc.perform(get(scoped("/api/admin/repository/search"))
                        .session(editor.session())
                        .param("query", "旅行可见"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andReturn());
        assertThat(visibleSearch.get("items").get(0).get("snippet").stringValue())
                .contains("旅行可见")
                .doesNotContain("https://", "hidden-destination", "[", "]");
        mvc.perform(get("/api/public/documents").param("query", "原文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(request(editor, create)).andExpect(status().isConflict());

        mvc.perform(get(scoped("/api/admin/repository/directory"))
                        .session(editor.session())
                        .param("commit", commit))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.commit").value(commit))
                .andExpect(jsonPath("$.entries[0].path").value("private"))
                .andExpect(jsonPath("$.entries[0].kind").value("DIRECTORY"));
        mvc.perform(get(scoped("/api/admin/repository/directory"))
                        .session(editor.session())
                        .param("commit", commit)
                        .param("path", "private"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value(path));
        var move = Map.of("baseCommit", commit, "source", path, "destination", "private/moved.md");
        mvc.perform(post(scoped("/api/admin/repository/move"))
                        .session(editor.session())
                        .contentType("application/json")
                        .content(json.writeValueAsString(move)))
                .andExpect(status().isForbidden());
        JsonNode moved = body(mvc.perform(post(scoped("/api/admin/repository/move"))
                        .session(editor.session())
                        .header(editor.header(), editor.token())
                        .contentType("application/json")
                        .content(json.writeValueAsString(move)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andReturn());
        mvc.perform(get(scoped("/api/admin/repository/directory"))
                        .session(editor.session())
                        .param("commit", moved.get("commit").stringValue())
                        .param("path", "private"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value("private/moved.md"));
        mvc.perform(get(scoped("/api/admin/repository/file"))
                        .session(editor.session())
                        .param("path", path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAbsence").value(true));
        JsonNode history = body(mvc.perform(get(scoped("/api/admin/repository/file"))
                        .session(editor.session())
                        .param("path", path)
                        .param("commit", commit))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(history.get("source").stringValue()).isEqualTo(source);
        mvc.perform(post(scoped("/api/admin/repository/move"))
                        .session(editor.session())
                        .header(editor.header(), editor.token())
                        .contentType("application/json")
                        .content(json.writeValueAsString(move)))
                .andExpect(status().isConflict());
        mvc.perform(post(scoped("/api/admin/repository/move"))
                        .session(editor.session())
                        .header(editor.header(), editor.token())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(editor, Map.of())).andExpect(status().isBadRequest());
        mvc.perform(request(editor, Map.of("changes", List.of(Map.of("content", "# missing path")))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(scoped("/api/admin/repository/search"))
                        .session(editor.session())
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(scoped("/api/admin/repository/tree")).session(editor.session()))
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
            JsonNode tree = http(client, "GET", scoped("/api/admin/repository/tree"), null, null, 200);
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
                routes.put("public/" + path, "/" + path.substring(0, path.length() - 3));
            }
            routes.put("public/目录 空格%#/index.md", "/目录 空格%#");
            routes.put("public/custom.md", "/explicit ?%# ");
            Map<String, String> sources = new LinkedHashMap<>();
            var changes = new ArrayList<Map<String, Object>>();
            changes.add(Map.of(
                    "path",
                    ".poketto/publishing.yaml",
                    "expectedAbsence",
                    true,
                    "content",
                    "enabled: true\nmode: public-root\n"));
            for (var entry : routes.entrySet()) {
                String source = entry.getKey().equals("public/custom.md")
                        ? "---\nroute: '/explicit ?%# '\n---\n# 原文\n"
                        : "# " + entry.getKey() + "\r\n保留原文。\r\n";
                sources.put(entry.getKey(), source);
                JsonNode preview = http(
                        client,
                        "POST",
                        scoped("/api/admin/repository/preview"),
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
                    scoped("/api/admin/repository/patch"),
                    csrf,
                    Map.of("baseCommit", tree.get("commit").stringValue(), "changes", changes),
                    200);
            assertThat(saved.get("committed").booleanValue()).isTrue();
            assertThat(saved.get("snapshotUpdated").booleanValue()).isTrue();
            String savedCommit = saved.get("commit").stringValue();
            JsonNode filenamePage = http(
                    client,
                    "GET",
                    scoped("/api/admin/repository/filenames?query=literal&offset=0&limit=1"),
                    null,
                    null,
                    200);
            assertThat(filenamePage.get("commit").stringValue()).isEqualTo(savedCommit);
            assertThat(filenamePage.get("total").intValue()).isEqualTo(2);
            assertThat(filenamePage.get("paths").get(0).stringValue()).isEqualTo("public/literal%2Fname.md");
            JsonNode filenameRemainder = http(
                    client,
                    "GET",
                    scoped("/api/admin/repository/filenames?query=literal&commit=" + savedCommit + "&offset=1&limit=1"),
                    null,
                    null,
                    200);
            assertThat(filenameRemainder.get("commit").stringValue()).isEqualTo(savedCommit);
            assertThat(filenameRemainder.get("paths").get(0).stringValue()).isEqualTo("public/literal/name.md");

            JsonNode advanced = http(
                    client,
                    "POST",
                    scoped("/api/admin/repository/patch"),
                    csrf,
                    Map.of(
                            "baseCommit",
                            savedCommit,
                            "changes",
                            List.of(Map.of(
                                    "path", "public/after-search.md", "expectedAbsence", true, "content", "# later"))),
                    200);
            assertThat(advanced.get("commit").stringValue()).isNotEqualTo(savedCommit);
            assertThat(http(
                                    client,
                                    "GET",
                                    scoped("/api/admin/repository/filenames?query=literal&commit=" + savedCommit),
                                    null,
                                    null,
                                    200)
                            .get("total")
                            .intValue())
                    .isEqualTo(2);
            createPublicOnlyMember();
            try (var memberClient = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()) {
                JsonNode memberCsrf = http(memberClient, "GET", "/api/auth/csrf", null, null, 200);
                var memberLogin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                        .timeout(Duration.ofSeconds(10))
                        .header(
                                memberCsrf.get("headerName").stringValue(),
                                memberCsrf.get("token").stringValue())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=filename-public&password=" + encode(password)))
                        .build();
                assertThat(memberClient
                                .send(memberLogin, HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                        .isEqualTo(204);
                JsonNode memberPage = http(
                        memberClient, "GET", scoped("/api/admin/repository/filenames?query=literal"), null, null, 200);
                assertThat(memberPage.get("total").intValue()).isEqualTo(2);
                assertThat(http(
                                        memberClient,
                                        "GET",
                                        scoped("/api/admin/repository/filenames?query=隐藏"),
                                        null,
                                        null,
                                        200)
                                .get("total")
                                .intValue())
                        .isZero();
                http(
                        memberClient,
                        "GET",
                        scoped("/api/admin/repository/filenames?query=literal&commit=" + savedCommit),
                        null,
                        null,
                        403);
            }
            for (var entry : routes.entrySet()) {
                JsonNode file = http(
                        client,
                        "GET",
                        scoped("/api/admin/repository/file?path=") + encode(entry.getKey()),
                        null,
                        null,
                        200);
                assertThat(file.get("source").stringValue()).isEqualTo(sources.get(entry.getKey()));
                assertThat(file.get("diagnostics").isEmpty()).isTrue();
                JsonNode document =
                        http(client, "GET", "/api/public/document?route=" + encode(entry.getValue()), null, null, 200);
                assertThat(document.get("route").stringValue()).isEqualTo(entry.getValue());
                assertThat(document.get("body").stringValue())
                        .contains(entry.getKey().equals("public/custom.md") ? "原文" : entry.getKey());
            }
            http(client, "GET", "/api/public/document?route=" + encode("/private/隐藏 %#"), null, null, 404);
            http(client, "GET", "/api/public/document?route=" + encode("/explicit ?%#"), null, null, 404);
            communityOverHttp(client, csrf);
            // Durable managed originals require native directory synchronization; CI exercises this on Linux.
            if (System.getProperty("os.name").equals("Linux")) {
                rawMediaUploadOverHttp(client, csrf);
            }
            overflowingGalleryOverHttp(client, csrf);
        }
    }

    private void communityOverHttp(HttpClient client, JsonNode csrf) throws Exception {
        UUID article = UUID.randomUUID();
        JsonNode tree = http(client, "GET", scoped("/api/admin/repository/tree"), null, null, 200);
        var patch = new RepositoryAdminController.PatchRequest(
                tree.get("commit").stringValue(),
                List.of(new RepositoryAdminController.Change(
                        "public/community.md", true, null, "---\nid: " + article + "\n---\n# Community")));
        http(client, "POST", scoped("/api/admin/repository/patch"), csrf, patch, 200);
        String space = jdbc.queryForObject(
                "select public_slug from workspaces where workspace_id=?",
                String.class,
                catalog.defaultWorkspace().id().value());
        String path = "/spaces/" + space + "/articles/" + article;
        String publicPath = "/api/public/community" + path;
        String privatePath = "/api/auth/community" + path;
        var input = new Community.CommentInput(UUID.randomUUID(), null, "😸".repeat(4000));
        http(client, "POST", privatePath + "/comments", null, input, 403);
        JsonNode created = http(client, "POST", privatePath + "/comments", csrf, input, 200);
        assertThat(http(client, "POST", privatePath + "/comments", csrf, input, 200))
                .isEqualTo(created);
        mvc.perform(get(publicPath))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.comments.items.length()").value(1))
                .andExpect(jsonPath("$.comments.items[0].body").value(input.body()))
                .andExpect(jsonPath("$.accountId").doesNotExist());
        http(client, "PUT", privatePath + "/relations/BOOKMARK", csrf, new CommunityController.Toggle(true), 204);
        assertThat(http(client, "GET", "/api/auth/community/bookmarks", null, null, 200)
                        .get("items")
                        .size())
                .isOne();
        mvc.perform(get("/api/auth/community/bookmarks")).andExpect(status().isUnauthorized());
        http(client, "GET", "/api/auth/community/feed?cursor=bm90LWpzb24", null, null, 400);
        jdbc.update(
                "update workspaces set public_delivery=false where workspace_id=?",
                catalog.defaultWorkspace().id().value());
        mvc.perform(get(publicPath)).andExpect(status().isNotFound());
        assertThat(http(client, "GET", "/api/auth/community/bookmarks", null, null, 200)
                        .get("items")
                        .get(0)
                        .get("article")
                        .isNull())
                .isTrue();
        jdbc.update(
                "update workspaces set public_delivery=true where workspace_id=?",
                catalog.defaultWorkspace().id().value());
    }

    private void createPublicOnlyMember() {
        UUID member = UUID.randomUUID();
        UUID workspace = catalog.defaultWorkspace().id().value();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) "
                        + "select ?, ?, password_hash from auth_accounts where login_name = ?",
                member,
                "filename-public",
                "editor");
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role,permissions) values (?,?,'MEMBER',?)",
                workspace,
                member,
                new String[0]);
    }

    private void overflowingGalleryOverHttp(HttpClient client, JsonNode csrf) throws Exception {
        Path checkout = directory.resolve("gallery-author");
        String source = "# 相册正文\n\n图片较多时正文仍可阅读。";
        try (Git git = Git.cloneRepository()
                .setURI(directory.resolve("remote.git").toUri().toString())
                .setDirectory(checkout.toFile())
                .call()) {
            Files.createDirectories(checkout.resolve("public/album"));
            Files.createDirectories(checkout.resolve("private"));
            Files.writeString(checkout.resolve("public/album/index.md"), source);
            Files.writeString(checkout.resolve("private/index.md"), "# Private album");
            Files.writeString(
                    checkout.resolve(".poketto/publishing.yaml"),
                    "enabled: true\nmode: public-root\nexclude: ['public/album/hidden-*.png']\n");
            var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < 129; i++) {
                for (String name : List.of(
                        "public/album/photo-%03d.png", "public/album/hidden-%03d.png", "private/image-%03d.png")) {
                    ImageIO.write(
                            image, "png", checkout.resolve(name.formatted(i)).toFile());
                }
            }
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Seed isolated gallery boundary")
                    .setAuthor("Fixture", "fixture@example.test")
                    .call();
            git.push().call();
        }
        snapshots.refresh(catalog.defaultWorkspace().id());
        JsonNode page = http(client, "GET", "/api/public/document?route=/album", null, null, 200);
        assertThat(page.get("body").stringValue()).isEqualTo(source);
        assertThat(page.get("galleryStatus").stringValue()).isEqualTo("PARTIAL");
        assertThat(page.get("gallery").size()).isEqualTo(128);
        assertThat(page.get("gallery").get(127).get("alt").stringValue()).isEqualTo("photo-127.png");
        assertThat(page.toString()).doesNotContain("hidden-", "private/", "repositoryPath", "diagnostics");
        JsonNode galleryImage = page.get("gallery").get(0);
        String imageUrl = galleryImage.get("src").stringValue();
        String originalUrl = galleryImage.get("original").stringValue();
        assertThat(imageUrl).startsWith("/api/public/assets/");
        assertThat(originalUrl).startsWith("/api/public/assets/");
        assertThat(imageUrl).isNotEqualTo(originalUrl);
        var thumbnail = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + imageUrl))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(thumbnail.statusCode()).isEqualTo(200);
        String thumbnailType = thumbnail.headers().firstValue("content-type").orElseThrow();
        assertThat(thumbnailType).isIn("image/png", "image/jpeg");
        try (var stream = new ByteArrayInputStream(thumbnail.body())) {
            var decoded = ImageIO.read(stream);
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isLessThanOrEqualTo(640);
            assertThat(decoded.getHeight()).isLessThanOrEqualTo(640);
        }
        var original = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + originalUrl))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(original.statusCode()).isEqualTo(200);
        assertThat(original.body()).isEqualTo(Files.readAllBytes(checkout.resolve("public/album/photo-000.png")));
        http(client, "GET", "/api/public/document?route=/private", null, null, 404);
        JsonNode preview = http(
                client,
                "POST",
                scoped("/api/admin/repository/preview"),
                csrf,
                Map.of("path", "private/index.md", "body", "# Private album"),
                200);
        assertThat(preview.get("galleryStatus").stringValue()).isEqualTo("PARTIAL");
        assertThat(preview.get("gallery").size()).isEqualTo(128);
        assertThat(preview.get("gallery").get(0).get("src").stringValue())
                .startsWith(scoped("/api/admin/assets/images/"));
        assertThat(preview.get("gallery").get(0).get("original").stringValue())
                .startsWith(scoped("/api/admin/assets/images/"));
        assertThat(preview.get("gallery").get(0).get("original").stringValue())
                .isEqualTo(preview.get("gallery").get(0).get("src").stringValue());
        try (Git git = Git.open(checkout.toFile())) {
            Files.writeString(
                    checkout.resolve(".poketto/publishing.yaml"),
                    "enabled: false\nmode: public-root\nexclude: ['public/album/hidden-*.png']\n");
            git.add().addFilepattern(".poketto/publishing.yaml").call();
            git.commit()
                    .setMessage("Withdraw gallery publication")
                    .setAuthor("Fixture", "fixture@example.test")
                    .call();
            git.push().call();
        }
        snapshots.refresh(catalog.defaultWorkspace().id());
        http(client, "GET", imageUrl, null, null, 404);
        http(client, "GET", originalUrl, null, null, 404);
    }

    private void rawMediaUploadOverHttp(HttpClient client, JsonNode csrf) throws Exception {
        String before = http(client, "GET", scoped("/api/admin/repository/tree"), null, null, 200)
                .get("commit")
                .stringValue();
        byte[] bytes = "raw original bytes".getBytes(StandardCharsets.UTF_8);
        String key = UUID.randomUUID().toString();
        var uri = URI.create("http://127.0.0.1:" + port + scoped("/api/admin/media"));
        var withoutCsrf = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/octet-stream")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        assertThat(client.send(withoutCsrf, HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(403);
        var wrongType = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header(csrf.get("headerName").stringValue(), csrf.get("token").stringValue())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("file=unparsed"))
                .build();
        assertThat(client.send(wrongType, HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(415);
        var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header(csrf.get("headerName").stringValue(), csrf.get("token").stringValue())
                .header("Content-Type", "application/octet-stream")
                .header("X-Media-Type", "application/pdf")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        var first = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        var repeated = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(repeated.statusCode()).isEqualTo(200);
        JsonNode uploaded = json.readTree(first.body());
        assertThat(json.readTree(repeated.body())).isEqualTo(uploaded);
        assertThat(uploaded.get("size").longValue()).isEqualTo(bytes.length);
        assertThat(uploaded.get("reference").get("revision").stringValue())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(http(client, "GET", scoped("/api/admin/repository/tree"), null, null, 200)
                        .get("commit")
                        .stringValue())
                .isEqualTo(before);
        var mediaEntry = Map.of(
                "assetId",
                uploaded.get("reference").get("assetId").stringValue(),
                "revision",
                uploaded.get("reference").get("revision").stringValue(),
                "mediaType",
                "application/pdf",
                "size",
                bytes.length);
        var mediaIndex = Map.of(
                "version", 1, "files", Map.of("public/source.pdf", mediaEntry, "private/source.pdf", mediaEntry));
        var publication = http(
                client,
                "POST",
                scoped("/api/admin/repository/patch"),
                csrf,
                Map.of(
                        "baseCommit",
                        before,
                        "changes",
                        List.of(
                                Map.of(
                                        "path",
                                        ".poketto/assets.json",
                                        "expectedAbsence",
                                        true,
                                        "content",
                                        json.writeValueAsString(mediaIndex)),
                                Map.of(
                                        "path",
                                        "public/http-media.md",
                                        "expectedAbsence",
                                        true,
                                        "content",
                                        "---\nroute: /http-media\n---\n[Download](source.pdf)\n"))),
                200);
        String query =
                "?commit=" + publication.get("commit").stringValue() + "&route=/http-media&path=public/source.pdf"
                        + "&workspace=" + catalog.defaultWorkspace().id();
        try (var anonymous =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (var reader : List.of(anonymous, client)) {
                String downloadPath = reader == anonymous
                        ? "/api/public/media" + query
                        : scoped("/api/admin/media?path=private/source.pdf");
                var download = reader.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + downloadPath))
                                .timeout(Duration.ofSeconds(15))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(download.statusCode()).isEqualTo(200);
                assertThat(download.body()).isEqualTo(bytes);
                assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
                        .startsWith("attachment;");
                assertThat(download.headers().firstValue("Cache-Control").orElseThrow())
                        .isEqualTo("no-store");
                assertThat(download.headers()
                                .firstValue("X-Content-Type-Options")
                                .orElseThrow())
                        .isEqualTo("nosniff");
            }
            http(anonymous, "GET", scoped("/api/admin/media?path=private/source.pdf"), null, null, 401);
            http(
                    anonymous,
                    "GET",
                    "/api/public/media" + query.replace("public/source.pdf", "private/source.pdf"),
                    null,
                    null,
                    404);
        }
        portableExportsOverHttp(client, csrf, bytes);
        playbackOverHttp(client, csrf);
    }

    private void playbackOverHttp(HttpClient client, JsonNode csrf) throws Exception {
        byte[] wave = new byte[16 * 1024];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wave, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wave, 8, 4);
        for (int index = 12; index < wave.length; index++) {
            wave[index] = (byte) index;
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + scoped("/api/admin/media")))
                .timeout(Duration.ofSeconds(15))
                .header(csrf.get("headerName").stringValue(), csrf.get("token").stringValue())
                .header("Content-Type", "application/octet-stream")
                .header("X-Media-Type", "audio/wav")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(wave))
                .build();
        var uploaded = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).isEqualTo(200);
        ManagedAsset asset = json.readValue(uploaded.body(), ManagedAsset.class);
        publishPlayback(client, csrf, asset);
        JsonNode page = http(client, "GET", "/api/public/document?route=/http-playback", null, null, 200);
        assertThat(page.get("playback").get("sound.wav").stringValue()).isEqualTo("audio");
        String address = page.get("downloads").get("sound.wav").stringValue() + "&play=true";
        try (var anonymous = HttpClient.newHttpClient()) {
            assertPlaybackHttp(anonymous, address, wave);
            assertPlaybackHttp(client, scoped("/api/admin/media?path=private/sound.wav&play=true"), wave);
            http(anonymous, "GET", address.replace("public%2Fsound.wav", "private%2Fsound.wav"), null, null, 404);
            http(anonymous, "GET", scoped("/api/admin/media?path=private/sound.wav&play=true"), null, null, 401);
            JsonNode article = http(
                    client, "GET", scoped("/api/admin/repository/file?path=public/http-playback.md"), null, null, 200);
            var removeReference = new RepositoryAdminController.Change(
                    "public/http-playback.md",
                    false,
                    article.get("revision").stringValue(),
                    "---\nroute: /http-playback\n---\nReference withdrawn\n");
            http(
                    client,
                    "POST",
                    scoped("/api/admin/repository/patch"),
                    csrf,
                    new RepositoryAdminController.PatchRequest(
                            article.get("commit").stringValue(), List.of(removeReference)),
                    200);
            http(anonymous, "GET", address, null, null, 404);
        }
    }

    private void publishPlayback(HttpClient client, JsonNode csrf, ManagedAsset asset) throws Exception {
        JsonNode current =
                http(client, "GET", scoped("/api/admin/repository/file?path=.poketto/assets.json"), null, null, 200);
        var entries = new TreeMap<>(
                RepositoryMediaIndex.parse(current.get("source").stringValue().getBytes(StandardCharsets.UTF_8))
                        .files());
        var entry = new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        entries.put("public/sound.wav", entry);
        entries.put("private/sound.wav", entry);
        var index = new RepositoryAdminController.Change(
                RepositoryMediaIndex.PATH,
                false,
                current.get("revision").stringValue(),
                new String(new RepositoryMediaIndex(entries).encode(), StandardCharsets.UTF_8));
        var article = new RepositoryAdminController.Change(
                "public/http-playback.md", true, null, "---\nroute: /http-playback\n---\n[Sound](sound.wav)\n");
        http(
                client,
                "POST",
                scoped("/api/admin/repository/patch"),
                csrf,
                new RepositoryAdminController.PatchRequest(
                        current.get("commit").stringValue(), List.of(index, article)),
                200);
    }

    private void assertPlaybackHttp(HttpClient client, String address, byte[] bytes) throws Exception {
        var uri = URI.create("http://127.0.0.1:" + port + address);
        for (String range : List.of("bytes=0-31", "bytes=8000-", "bytes=-17")) {
            int start = range.equals("bytes=0-31") ? 0 : range.equals("bytes=8000-") ? 8000 : bytes.length - 17;
            int end = range.equals("bytes=0-31") ? 32 : bytes.length;
            var response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(15))
                            .header("Range", range)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.body()).containsExactly(Arrays.copyOfRange(bytes, start, end));
            assertThat(response.headers().firstValue("Content-Range").orElseThrow())
                    .isEqualTo("bytes " + start + "-" + (end - 1) + "/" + bytes.length);
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo(String.valueOf(end - start));
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("audio/wav");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                    .startsWith("inline;");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .isEqualTo("no-store");
            assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow())
                    .isEqualTo("nosniff");
        }
        var invalid = client.send(
                HttpRequest.newBuilder(uri)
                        .header("Range", "bytes=9999999-")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(invalid.statusCode()).isEqualTo(416);
        assertThat(invalid.headers().firstValue("Content-Range").orElseThrow()).isEqualTo("bytes */" + bytes.length);
        var head = client.send(
                HttpRequest.newBuilder(uri)
                        .header("Range", "bytes=0-1")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Length").orElseThrow()).isEqualTo(String.valueOf(bytes.length));
        var ifRange = client.send(
                HttpRequest.newBuilder(uri)
                        .header("Range", "bytes=0-1")
                        .header("If-Range", "unknown")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ifRange.statusCode()).isEqualTo(200);
        assertThat(ifRange.body()).containsExactly(bytes);
    }

    private void portableExportsOverHttp(HttpClient client, JsonNode csrf, byte[] original) throws Exception {
        var selection = Map.of("paths", List.of("public/http-media.md"), "publicOnly", true);
        http(client, "POST", scoped("/api/admin/exports"), null, selection, 403);
        var receipt = http(client, "POST", scoped("/api/admin/exports"), csrf, selection, 200);
        String handle = receipt.path("handle").stringValue();
        try (var anonymous = HttpClient.newHttpClient()) {
            http(anonymous, "GET", scoped("/api/admin/exports/") + handle, null, null, 401);
        }
        var downloaded = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + scoped("/api/admin/exports/") + handle))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/zip");
        assertThat(downloaded.headers().firstValue("Content-Disposition").orElseThrow())
                .contains("attachment", "poketto-public.zip");
        assertThat(downloaded.headers().firstValue("Cache-Control").orElseThrow())
                .isEqualTo("no-store");
        assertThat(downloaded.body().length).isEqualTo(receipt.path("bytes").longValue());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded.body())))
                .isEqualTo(receipt.path("sha256").stringValue());
        var entries = new TreeMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(downloaded.body()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertThat(entries).hasSize(2);
        assertThat(entries.get("media/original-1.pdf")).containsExactly(original);
        assertThat(new String(entries.get("content/article-1.md"), StandardCharsets.UTF_8))
                .contains("../media/original-1.pdf")
                .doesNotContain("managed:", "private/", ".poketto/");
        http(
                client,
                "POST",
                scoped("/api/admin/exports"),
                csrf,
                Map.of("paths", List.of("private/source.pdf"), "publicOnly", true),
                503);
        http(client, "POST", scoped("/api/admin/exports/") + handle + "/release", null, Map.of(), 403);
        var release = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + scoped("/api/admin/exports/") + handle + "/release"))
                .header(
                        csrf.path("headerName").stringValue(),
                        csrf.path("token").stringValue())
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertThat(client.send(release, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(204);
        http(client, "GET", scoped("/api/admin/exports/") + handle + "/metadata", null, null, 404);
    }

    private void verifyIdentityDraft(Csrf editor) throws Exception {
        String endpoint = scoped("/api/admin/repository/article-identity");
        String source = "# Draft\n" + "原文".repeat(10_000);
        String payload =
                json.writeValueAsString(new RepositoryAdminController.IdentityRequest("private/note.md", source));
        mvc.perform(post(endpoint).contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint)
                        .session(editor.session())
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isForbidden());
        JsonNode draft = body(mvc.perform(post(endpoint)
                        .session(editor.session())
                        .header(editor.header(), editor.token())
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn());
        UUID id = UUID.fromString(draft.get("articleId").stringValue());
        assertThat(draft.get("source").stringValue()).isEqualTo("---\nid: " + id + "\n---\n" + source);
        mvc.perform(get(scoped("/api/admin/repository/file"))
                        .session(editor.session())
                        .param("path", "private/note.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAbsence").value(true));
    }

    private JsonNode http(HttpClient client, String method, String path, JsonNode csrf, Object payload, int status)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (csrf != null) {
            request.header(
                    csrf.get("headerName").stringValue(), csrf.get("token").stringValue());
        }
        if (payload == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(status);
        return json.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode token = body(result);
        return new Csrf(
                (MockHttpSession) result.getRequest().getSession(),
                token.get("headerName").stringValue(),
                token.get("token").stringValue());
    }

    private MockHttpServletRequestBuilder request(Csrf csrf, Object body) {
        return post(scoped("/api/admin/repository/patch"))
                .session(csrf.session())
                .header(csrf.header(), csrf.token())
                .contentType("application/json")
                .content(json.writeValueAsString(body));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record Csrf(MockHttpSession session, String header, String token) {}

    private String scoped(String path) {
        String workspace = catalog.defaultWorkspace().id().toString();
        if (path.equals("/api/auth/me")) {
            return "/api/auth/workspaces/" + workspace + "/me";
        }
        return "/api/admin/workspaces/" + workspace + path.substring("/api/admin".length());
    }
}
