package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@RecordApplicationEvents
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RemoteRepositoryIntegrationConfiguration.class)
class McpProtocolIntegrationIT {
    @TempDir
    static Path directory;

    private static final String INITIALIZATION = UUID.randomUUID().toString();
    private static final byte[] PNG = png();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            // JUnit may receive a Windows short-name temp path; production storage rejects aliases.
            directory = directory.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        Path remote = directory.resolve("remote.git");
        try (Git remoteGit = Git.init()
                        .setBare(true)
                        .setInitialBranch("main")
                        .setDirectory(remote.toFile())
                        .call();
                Git seed = Git.cloneRepository()
                        .setURI(remote.toUri().toString())
                        .setDirectory(directory.resolve("seed").toFile())
                        .call()) {
            // An empty remote has no advertised branch for clone to select.
            seed.getRepository().updateRef("HEAD").link("refs/heads/main");
            Path tree = seed.getRepository().getWorkTree().toPath();
            Files.createDirectories(tree.resolve("private"));
            Files.writeString(tree.resolve("private/original.md"), "# Original\n");
            Files.write(tree.resolve("private/pixel.png"), PNG);
            seed.add().addFilepattern(".").call();
            seed.commit()
                    .setMessage("Synthetic MCP fixture")
                    .setAuthor("Test", "test@invalid")
                    .call();
            seed.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
            assertThat(remoteGit.getRepository().resolve("refs/heads/main"))
                    .isEqualTo(seed.getRepository().resolve("HEAD"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        registry.add("poketto.test.repository-path", remote::toString);
        registry.add("poketto.data-dir", directory::toString);
        registry.add("poketto.auth.initialization-token", () -> INITIALIZATION);
    }

    @Autowired
    AuthService auth;

    @Autowired
    WorkspaceCatalog workspaces;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationEvents events;

    @LocalServerPort
    int port;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private int sequence;

    @Test
    void realStreamableRequestsBindKeysReadExactObjectsPatchAtomicallyAndRevokeSessions() throws Exception {
        var owner = auth.initializeOwner(
                INITIALIZATION, "mcp-owner", UUID.randomUUID().toString());
        var workspace = workspaces.defaultWorkspace().id();
        var key = auth.createApiKey(owner, workspace, owner.accountId(), null);
        var other = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        var denied = auth.createApiKey(owner, workspace, owner.accountId(), Set.of());
        assertThat(post(null, null, initialize()).statusCode()).isEqualTo(401);
        String first = initialize(key.token());
        String second = initialize(key.token());
        assertThat(second).isNotEqualTo(first);
        assertThat(post(other.token(), first, rpc("tools/list", Map.of())).statusCode())
                .isEqualTo(404);
        assertThat(post(key.token(), "caller-invented-session", rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(404);
        JsonNode tools = response(post(key.token(), first, rpc("tools/list", Map.of())))
                .path("result")
                .path("tools");
        assertThat(tools.valueStream()
                        .map(tool -> tool.path("name").stringValue())
                        .toList())
                .contains("get_file", "repo_patch", "get_asset", "put_asset")
                .doesNotContain("repo_exec");
        JsonNode original = result(call(key.token(), first, "get_file", Map.of("path", "private/original.md")));
        String base = original.path("commit").stringValue();
        assertRequestErrorBoundary(key.token(), first, base);
        assertThat(original.path("source").stringValue())
                .withFailMessage("Authoritative file response: %s", original)
                .isEqualTo("# Original\n");
        assertThat(original.path("revision").stringValue()).startsWith("sha256:");
        assertThat(result(call(key.token(), first, "get_file", Map.of("path", "private/missing.md")))
                        .path("expectedAbsence")
                        .booleanValue())
                .isTrue();
        String source = "\uFEFF---\r\nunknown: untouched\r\n---\r\n# 中文\r\n原始字节\r\n";
        var patch = Map.of(
                "baseCommit",
                base,
                "changes",
                List.of(Map.of("path", "private/中文.md", "expectedAbsence", true, "content", source)));
        JsonNode written = result(call(key.token(), first, "repo_patch", patch));
        assertThat(written.path("committed").booleanValue()).isTrue();
        assertThat(written.path("snapshotUpdated").booleanValue()).isTrue();
        assertThat(result(call(key.token(), second, "get_file", Map.of("path", "private/中文.md")))
                        .path("source")
                        .stringValue())
                .isEqualTo(source);
        assertThat(result(call(key.token(), first, "get_file", Map.of("path", "private/中文.md", "commit", base)))
                        .path("expectedAbsence")
                        .booleanValue())
                .isTrue();
        assertThat(error(call(key.token(), first, "repo_patch", patch))).isEqualTo("CONFLICT");
        Map<String, Object> deletion = new LinkedHashMap<>();
        deletion.put("path", "private/中文.md");
        deletion.put("expectedAbsence", false);
        deletion.put(
                "expectedRevision",
                written.path("revisions").path("private/中文.md").stringValue());
        deletion.put("content", null);
        Map<String, Object> missingContent = new LinkedHashMap<>(deletion);
        missingContent.remove("content");
        JsonNode rejected = call(
                key.token(),
                first,
                "repo_patch",
                Map.of("baseCommit", written.path("commit").stringValue(), "changes", List.of(missingContent)));
        assertThat(rejected.path("isError").booleanValue()).isTrue();
        assertThat(rejected.path("content").get(0).path("text").stringValue()).contains("content");
        var move = Map.of(
                "baseCommit",
                written.path("commit").stringValue(),
                "changes",
                List.of(deletion, Map.of("path", "private/moved.md", "expectedAbsence", true, "content", source)));
        JsonNode moved = result(call(key.token(), first, "repo_patch", move));
        assertThat(moved.path("committed").booleanValue()).isTrue();
        assertThat(result(call(key.token(), first, "get_file", Map.of("path", "private/中文.md")))
                        .path("expectedAbsence")
                        .booleanValue())
                .isTrue();
        JsonNode movedFile = result(call(key.token(), first, "get_file", Map.of("path", "private/moved.md")));
        assertThat(movedFile.path("source").stringValue()).isEqualTo(source);
        deletion.put("path", "private/moved.md");
        deletion.put("expectedRevision", movedFile.path("revision").stringValue());
        Map<String, Object> nullBase = new LinkedHashMap<>();
        nullBase.put("baseCommit", null);
        nullBase.put("changes", List.of(deletion));
        assertThat(error(call(key.token(), first, "repo_patch", nullBase))).isEqualTo("CONFLICT");
        assertThat(result(call(
                                key.token(),
                                first,
                                "repo_patch",
                                Map.of(
                                        "baseCommit",
                                        movedFile.path("commit").stringValue(),
                                        "changes",
                                        List.of(deletion))))
                        .path("committed")
                        .booleanValue())
                .isTrue();
        assertThat(result(call(key.token(), first, "get_file", Map.of("path", "private/moved.md")))
                        .path("expectedAbsence")
                        .booleanValue())
                .isTrue();
        String deniedSession = initialize(denied.token());
        assertThat(error(call(denied.token(), deniedSession, "get_file", Map.of("path", "private/original.md"))))
                .isEqualTo("DENIED");
        JsonNode image = call(
                key.token(),
                first,
                "get_asset",
                Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png", "commit", base)));
        assertThat(image.path("isError").booleanValue())
                .withFailMessage("Exact image response: %s", image)
                .isFalse();
        assertThat(image.path("content").get(1).path("type").stringValue()).isEqualTo("image");
        assertThat(Base64.getDecoder()
                        .decode(image.path("content").get(1).path("data").stringValue()))
                .isEqualTo(PNG);
        assertThat(error(call(
                        other.token(),
                        initialize(other.token()),
                        "put_asset",
                        Map.of("operationKey", UUID.randomUUID().toString(), "base64", "AA=="))))
                .isEqualTo("DENIED");
        JsonNode malformed = call(key.token(), first, "repo_patch", Map.of("changes", List.of()));
        // SDK schema validation runs before the business callback and returns its own error text.
        assertThat(malformed.path("isError").booleanValue()).isTrue();
        assertThat(malformed.path("content").get(0).path("text").stringValue()).contains("baseCommit");
        var delete = HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + key.token())
                .header("Mcp-Session-Id", second)
                .DELETE()
                .build();
        assertThat(http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
        assertThat(post(key.token(), second, rpc("tools/list", Map.of())).statusCode())
                .isEqualTo(404);
        auth.revokeApiKey(owner, workspace, key.id());
        assertThat(post(key.token(), first, rpc("tools/list", Map.of())).statusCode())
                .isEqualTo(401);
        assertThat(events.stream(McpSessionClosed.class)
                        .map(McpSessionClosed::reason)
                        .toList())
                .contains(McpSessionClosed.Reason.AUTH_REVOKED);
    }

    private void assertRequestErrorBoundary(String token, String session, String base) throws Exception {
        byte[] operation = JsonMapper.shared()
                .writeValueAsBytes(rpc(
                        "tools/call",
                        Map.of(
                                "name",
                                "repo_patch",
                                "arguments",
                                Map.of(
                                        "baseCommit",
                                        base,
                                        "changes",
                                        List.of(Map.of(
                                                "path",
                                                "private/oversized-request.md",
                                                "expectedAbsence",
                                                true,
                                                "content",
                                                "# Must not execute\n"))))));
        byte[] oversized = padded(operation, McpBodyLimitFilter.MAX_REQUEST_BYTES + 128);
        for (boolean chunked : List.of(true, false)) {
            var rejected = rawPost(token, session, oversized, chunked);
            assertThat(rejected.statusCode()).isEqualTo(413);
            assertThat(rejected.body()).doesNotContain("stackTrace", "className", "jsonRpcError");
            assertThat(rejected.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(result(call(token, session, "get_file", Map.of("path", "private/oversized-request.md")))
                            .path("expectedAbsence")
                            .booleanValue())
                    .isTrue();
        }
        var boundary = rawPost(
                token,
                session,
                padded(
                        JsonMapper.shared().writeValueAsBytes(rpc("tools/list", Map.of())),
                        McpBodyLimitFilter.MAX_REQUEST_BYTES),
                true);
        assertThat(response(boundary).path("result").path("tools").isArray()).isTrue();
        var malformed = rawPost(token, session, new byte[] {'{'}, true);
        assertThat(malformed.statusCode()).isEqualTo(400);
        var error = json.readTree(malformed.body());
        assertThat(error.path("jsonrpc").stringValue()).isEqualTo("2.0");
        assertThat(error.has("id")).isFalse();
        assertThat(error.path("error").path("code").intValue()).isEqualTo(-32600);
        assertThat(error.path("error").path("message").stringValue()).isEqualTo("Invalid message format");
        assertThat(malformed.body()).doesNotContain("stackTrace", "className", "localizedMessage", "jsonRpcError");
        assertThat(post(token, session, rpc("tools/list", Map.of())).statusCode())
                .isEqualTo(200);
    }

    private static byte[] padded(byte[] operation, int size) {
        byte[] body = new byte[size];
        java.util.Arrays.fill(body, (byte) ' ');
        System.arraycopy(operation, 0, body, size - operation.length, operation.length);
        return body;
    }

    private HttpResponse<String> rawPost(String token, String session, byte[] body, boolean chunked) throws Exception {
        var publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        assertThat(publisher.contentLength()).isEqualTo(chunked ? -1L : body.length);
        var request = HttpRequest.newBuilder(endpoint())
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + token)
                .header("Mcp-Session-Id", session)
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(publisher)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    private static byte[] png() {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> initialize() {
        return rpc(
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2025-11-25",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "integration", "version", "1")));
    }

    private String initialize(String token) throws Exception {
        var response = post(token, null, initialize());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response(response)
                        .path("result")
                        .path("serverInfo")
                        .path("name")
                        .stringValue())
                .isEqualTo("poketto");
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(post(token, session, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                        .statusCode())
                .isEqualTo(202);
        return session;
    }

    private Map<String, Object> rpc(String method, Object params) {
        return Map.of("jsonrpc", "2.0", "id", ++sequence, "method", method, "params", params);
    }

    private HttpResponse<String> post(String token, String session, Object body) throws Exception {
        var request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.shared().writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (session != null) request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode response(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        String body = response.body();
        if (body.stripLeading().startsWith("{")) return json.readTree(body);
        return json.readTree(body.lines()
                .filter(line -> line.startsWith("data:"))
                .reduce((a, b) -> b)
                .orElseThrow()
                .substring(5)
                .strip());
    }

    private JsonNode call(String token, String session, String tool, Object arguments) throws Exception {
        JsonNode envelope =
                response(post(token, session, rpc("tools/call", Map.of("name", tool, "arguments", arguments))));
        assertThat(envelope.has("error")).withFailMessage(envelope.toString()).isFalse();
        return envelope.path("result");
    }

    private JsonNode result(JsonNode call) {
        assertThat(call.path("isError").booleanValue())
                .withFailMessage(call.toString())
                .isFalse();
        return json.readTree(call.path("content").get(0).path("text").stringValue());
    }

    private String error(JsonNode call) {
        assertThat(call.path("isError").booleanValue())
                .withFailMessage(call.toString())
                .isTrue();
        return json.readTree(call.path("content").get(0).path("text").stringValue())
                .path("code")
                .stringValue();
    }
}
