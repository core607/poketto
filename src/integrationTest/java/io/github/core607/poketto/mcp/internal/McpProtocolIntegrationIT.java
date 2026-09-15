package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.RegistrationService;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
            Files.createDirectories(tree.resolve("topics"));
            Files.writeString(tree.resolve("AGENTS.md"), "# Repository guide\nSee topics/AGENTS.md.\n");
            Files.writeString(tree.resolve("topics/AGENTS.md"), "# Topics\nMaintain the existing list.\n");
            Files.writeString(tree.resolve("topics/list.txt"), "An existing item\n");
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
        registry.add("poketto.oauth.issuer", () -> "https://transfer.example.com");
    }

    @Autowired
    AuthService auth;

    @Autowired
    RegistrationService registration;

    @Autowired
    WorkspaceRegistry registry;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    WorkspaceCatalog workspaces;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationEvents events;

    @MockitoSpyBean
    AssetService assets;

    @Autowired
    ImageMemoryAdmission imageMemory;

    @LocalServerPort
    int port;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private int sequence;

    @Test
    void realStreamableRequestsBindKeysServeMediaRejectFileCrudAndRevokeSessions() throws Exception {
        var owner = auth.initializeOwner("mcp-owner", UUID.randomUUID().toString());
        var workspace = workspaces.defaultWorkspace().id();
        var key = auth.createApiKey(owner, workspace, owner.accountId(), null);
        var other = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        var denied = auth.createApiKey(owner, workspace, owner.accountId(), Set.of());
        assertThat(post(null, null, initialize()).statusCode()).isEqualTo(401);
        String first = initialize(key.token());
        String second = initialize(key.token());
        assertMemberScopeRevocation(owner, workspace);
        var separateOwner = registration.register(
                registration.issue(owner).token(),
                "separate-mcp-owner",
                UUID.randomUUID().toString());
        var separateSpace = WorkspaceId.random();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            registry.create(separateSpace, "Separate connector space", "separate-connector");
            auth.establishWorkspaceOwner(separateOwner, separateSpace);
        });
        var separateKey = auth.createApiKey(
                separateOwner, separateSpace, separateOwner.accountId(), Set.of(Capability.READ_PRIVATE));
        // This account has no default-space membership. Its credential still initializes on /mcp.
        String separateSession = initialize(separateKey.token());
        assertThat(post(separateKey.token(), first, rpc("tools/list", Map.of())).statusCode())
                .isEqualTo(404);
        assertThat(post(key.token(), separateSession, rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(404);
        assertThat(post(separateKey.token(), separateSession, rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(200);
        auth.revokeApiKey(separateOwner, separateSpace, separateKey.id());
        assertThat(post(separateKey.token(), separateSession, rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(401);
        assertActualImageResponseCompletion(key.token(), first);
        assertEnvelopeLimits(key.token(), first);
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
                .containsExactlyInAnyOrder("get_asset", "put_asset");
        assertRemovedFileTools(key.token(), first);
        assertRequestErrorBoundary(key.token(), first);
        assertImageTransferEntrance(owner, workspace, key.token(), first, other.token());
        String deniedSession = initialize(denied.token());
        assertThat(error(call(
                        denied.token(),
                        deniedSession,
                        "get_asset",
                        Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png")))))
                .isEqualTo("DENIED");
        JsonNode image = call(
                key.token(),
                first,
                "get_asset",
                Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png")));
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
                        Map.of("operationKey", UUID.randomUUID().toString(), "mode", "upload"))))
                .isEqualTo("DENIED");
        JsonNode malformed = call(key.token(), first, "put_asset", Map.of("base64", "AA=="));
        // SDK schema validation runs before the business callback and returns its own error text.
        assertThat(malformed.path("isError").booleanValue()).isTrue();
        assertThat(malformed.path("content").get(0).path("text").stringValue()).contains("operationKey");
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

    private void assertImageTransferEntrance(
            AuthPrincipal owner, WorkspaceId workspace, String token, String session, String reader) throws Exception {
        JsonNode catalog = response(post(token, session, rpc("tools/list", Map.of())))
                .path("result")
                .path("tools");
        JsonNode tool = catalog.valueStream()
                .filter(item -> item.path("name").asString().equals("put_asset"))
                .findFirst()
                .orElseThrow();
        assertThat(tool.path("_meta").path("openai/fileParams").get(0).asString())
                .isEqualTo("file");
        assertThat(tool.path("inputSchema")
                        .path("properties")
                        .path("file")
                        .path("properties")
                        .propertyNames())
                .containsExactlyInAnyOrder("download_url", "file_id", "mime_type", "file_name");
        var request = Map.of("mode", "upload", "operationKey", UUID.randomUUID().toString());
        JsonNode grant = json.readTree(call(token, session, "put_asset", request)
                .path("content")
                .get(0)
                .path("text")
                .asString());
        URI target = endpoint()
                .resolve(URI.create(grant.path("uploadUrl").asString()).getPath());
        assertThat(http.send(HttpRequest.newBuilder(target).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(409);
        var wrongType = HttpRequest.newBuilder(target)
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("not an image"))
                .build();
        assertThat(http.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(415);
        assertRawUploadLeavesPageBudget(target, owner, workspace);
        assertThat(error(call(reader, initialize(reader), "put_asset", request)))
                .isEqualTo("DENIED");
        assertThat(error(call(
                        token,
                        session,
                        "put_asset",
                        Map.of("operationKey", UUID.randomUUID().toString(), "url", "https://127.0.0.1/private"))))
                .isEqualTo("SOURCE_UNAVAILABLE");
        var temporary = auth.createApiKey(owner, workspace, owner.accountId(), null);
        JsonNode revoked = json.readTree(call(temporary.token(), initialize(temporary.token()), "put_asset", request)
                .path("content")
                .get(0)
                .path("text")
                .asString());
        URI revokedTarget = endpoint()
                .resolve(URI.create(revoked.path("uploadUrl").asString()).getPath());
        auth.revokeApiKey(owner, workspace, temporary.id());
        assertThat(http.send(HttpRequest.newBuilder(revokedTarget).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(403);
    }

    private void assertRawUploadLeavesPageBudget(URI target, AuthPrincipal owner, WorkspaceId workspace)
            throws Exception {
        var member = registration.register(
                registration.issue(owner).token(),
                "concurrent-uploader",
                UUID.randomUUID().toString());
        auth.acceptInvitation(
                member,
                auth.createInvitation(owner, workspace, Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE))
                        .token());
        var key = auth.createApiKey(
                owner, workspace, member.accountId(), Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE));
        String session = initialize(key.token());
        try (var slow = new Socket(target.getHost(), target.getPort())) {
            slow.setSoTimeout(5000);
            var output = slow.getOutputStream();
            String headers = "PUT " + target.getRawPath() + " HTTP/1.1\r\nHost: " + target.getAuthority()
                    + "\r\nContent-Type: application/octet-stream\r\nConnection: close\r\nContent-Length: "
                    + PNG.length + "\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(PNG, 0, 8);
            output.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (imageMemory.reservedBytes() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(imageMemory.reservedBytes()).isEqualTo(32L * 1024 * 1024);
            var competing = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG))
                    .build();
            assertThat(http.send(competing, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(429);
            var page =
                    imageMemory.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
            page.responseComplete();
            JsonNode other = result(call(
                    key.token(),
                    session,
                    "put_asset",
                    Map.of("mode", "upload", "operationKey", UUID.randomUUID().toString())));
            URI otherTarget = endpoint()
                    .resolve(URI.create(other.path("uploadUrl").asString()).getPath());
            var otherUpload = HttpRequest.newBuilder(otherTarget)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG))
                    .build();
            assertThat(http.send(otherUpload, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
            output.write(PNG, 8, PNG.length - 8);
            output.flush();
            assertThat(new String(slow.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .startsWith("HTTP/1.1 200");
        }
        assertThat(http.send(HttpRequest.newBuilder(target).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
    }

    private void assertMemberScopeRevocation(AuthPrincipal owner, WorkspaceId workspace) throws Exception {
        var member = registration.register(
                registration.issue(owner).token(),
                "scoped-mcp-member",
                UUID.randomUUID().toString());
        auth.acceptInvitation(
                member,
                auth.createInvitation(owner, workspace, Set.of(Capability.READ_PRIVATE))
                        .token());
        var privateKey = auth.createApiKey(owner, workspace, member.accountId(), Set.of(Capability.READ_PRIVATE));
        var publicKey = auth.createApiKey(owner, workspace, member.accountId(), Set.of(Capability.EXECUTE_REPOSITORY));
        String privateSession = initialize(privateKey.token());
        String publicSession = initialize(publicKey.token());
        var source = Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png"));
        assertThat(call(privateKey.token(), privateSession, "get_asset", source)
                        .path("isError")
                        .asBoolean())
                .isFalse();
        assertThat(error(call(publicKey.token(), publicSession, "get_asset", source)))
                .isEqualTo("DENIED");
        auth.changeMembership(owner, workspace, member.accountId(), MembershipRole.MEMBER, true, Set.of());
        assertThat(post(privateKey.token(), privateSession, rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(401);
        assertThat(post(publicKey.token(), publicSession, rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(200);
        assertThat(events.stream(McpSessionClosed.class)
                        .filter(event -> event.keyId().equals(privateKey.id()))
                        .map(McpSessionClosed::reason)
                        .toList())
                .contains(McpSessionClosed.Reason.AUTH_REVOKED);
        auth.changeMembership(
                owner, workspace, member.accountId(), MembershipRole.MEMBER, true, Set.of(Capability.READ_PRIVATE));
        assertThat(error(call(publicKey.token(), publicSession, "get_asset", source)))
                .isEqualTo("DENIED");
        assertThat(post(privateKey.token(), privateSession, rpc("tools/list", Map.of()))
                        .statusCode())
                .isEqualTo(401);
    }

    private void assertRemovedFileTools(String token, String session) throws Exception {
        for (String name : List.of("list_directory", "get_file", "repo_patch")) {
            JsonNode reply =
                    response(post(token, session, rpc("tools/call", Map.of("name", name, "arguments", Map.of()))));
            assertThat(reply.has("error")).withFailMessage(reply.toString()).isTrue();
        }
    }

    private void assertRequestErrorBoundary(String token, String session) throws Exception {
        byte[] operation = JsonMapper.shared()
                .writeValueAsBytes(rpc(
                        "tools/call",
                        Map.of(
                                "name",
                                "put_asset",
                                "arguments",
                                Map.of(
                                        "operationKey",
                                        "oversized_request_001",
                                        "base64",
                                        Base64.getEncoder().encodeToString(PNG)))));
        byte[] oversized = padded(operation, McpBodyLimitFilter.MAX_REQUEST_BYTES + 128);
        for (boolean chunked : List.of(true, false)) {
            Mockito.clearInvocations(assets);
            var rejected = rawPost(token, session, oversized, chunked);
            assertThat(rejected.statusCode()).isEqualTo(413);
            assertThat(rejected.body()).doesNotContain("stackTrace", "className", "jsonRpcError");
            assertThat(rejected.headers().firstValue("Cache-Control")).contains("no-store");
            Mockito.verify(assets, Mockito.never())
                    .upload(
                            ArgumentMatchers.any(), ArgumentMatchers.any(),
                            ArgumentMatchers.anyString(), ArgumentMatchers.any());
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
        Arrays.fill(body, (byte) ' ');
        System.arraycopy(operation, 0, body, size - operation.length, operation.length);
        return body;
    }

    private void assertEnvelopeLimits(String token, String session) throws Exception {
        var legal = Map.of("jsonrpc", "2.0", "id", "图".repeat(128), "method", "tools/list");
        assertThat(response(post(token, session, legal)).path("id").stringValue())
                .isEqualTo("图".repeat(128));
        for (Object id : List.of("图".repeat(129), "x".repeat(16384), BigInteger.TEN.pow(128))) {
            var invalid = post(token, session, Map.of("jsonrpc", "2.0", "id", id, "method", "tools/list"));
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).hasSizeLessThan(200);
            assertThat(json.readTree(invalid.body()).has("id")).isFalse();
        }
        var imageRequest = rpc(
                "tools/call",
                Map.of(
                        "name",
                        "get_asset",
                        "arguments",
                        Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png"))));
        assertThat(response(rawPost(token, session, padded(json.writeValueAsBytes(imageRequest), 16384), false))
                        .path("result")
                        .path("isError")
                        .booleanValue())
                .isFalse();
        assertThat(rawPost(
                                token,
                                session,
                                padded(json.writeValueAsBytes(imageRequest), McpBodyLimitFilter.MAX_REQUEST_BYTES + 1),
                                true)
                        .statusCode())
                .isEqualTo(413);
        for (String body : List.of("[".repeat(33) + "]".repeat(33), "[" + "[],".repeat(2050) + "[]]")) {
            assertThat(rawPost(token, session, body.getBytes(StandardCharsets.UTF_8), false)
                            .statusCode())
                    .isEqualTo(413);
        }
        assertThat(post(
                                token,
                                null,
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        "x".repeat(129),
                                        "method",
                                        "initialize",
                                        "params",
                                        Map.of()))
                        .statusCode())
                .isEqualTo(400);
        assertThat(imageMemory.reservedBytes()).isZero();
    }

    private void assertActualImageResponseCompletion(String token, String session) throws Exception {
        var held = imageMemory.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        try {
            JsonNode refused = call(
                    token,
                    session,
                    "get_asset",
                    Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png")));
            assertThat(error(refused)).isEqualTo("UNAVAILABLE");
            assertThat(json.readTree(refused.path("content").get(0).path("text").stringValue())
                            .path("reason")
                            .stringValue())
                    .isEqualTo("IMAGE_MEMORY_BUSY");
            assertThat(response(post(token, session, rpc("tools/list", Map.of())))
                            .path("result")
                            .path("tools")
                            .isArray())
                    .isTrue();
            assertThat(call(
                                    token,
                                    session,
                                    "put_asset",
                                    Map.of(
                                            "mode",
                                            "upload",
                                            "operationKey",
                                            UUID.randomUUID().toString()))
                            .path("isError")
                            .booleanValue())
                    .isFalse();
        } finally {
            held.responseComplete();
        }
        for (int i = 0; i < 24; i++) {
            String current = i % 2 == 0 ? initialize(token) : session;
            JsonNode image = call(
                    token,
                    current,
                    "get_asset",
                    Map.of("source", Map.of("kind", "repository", "path", "private/pixel.png")));
            assertThat(image.path("isError").booleanValue()).isFalse();
            if (i % 2 == 0) {
                var delete = HttpRequest.newBuilder(endpoint())
                        .header("Authorization", "Bearer " + token)
                        .header("Mcp-Session-Id", current)
                        .DELETE()
                        .build();
                assertThat(http.send(delete, HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                        .isEqualTo(200);
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (imageMemory.reservedBytes() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(imageMemory.reservedBytes()).isZero();
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
        } catch (IOException exception) {
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
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode response(HttpResponse<String> response) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        String body = response.body();
        if (body.stripLeading().startsWith("{")) {
            return json.readTree(body);
        }
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
