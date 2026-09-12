package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpArtifactTests {
    @Test
    void explicit64KiBPagesMatchTheSchemaAndLargerRequestsNeverReachTheExecutor() throws Exception {
        String source = "a".repeat(65535) + "猫末尾";
        try (var fixture = new Fixture(source.getBytes(StandardCharsets.UTF_8), "text/plain")) {
            var schema = fixture.json.valueToTree(fixture.tool.tool().inputSchema());
            assertThat(schema.path("properties").path("limit").path("maximum").intValue())
                    .isEqualTo(65536);
            var first = fixture.call(Map.of("artifactId", fixture.id, "limit", 65536));
            assertThat(first.isError()).isFalse();
            assertThat(fixture.info(first).path("nextOffset").longValue()).isEqualTo(65535);
            var second = fixture.call(Map.of("artifactId", fixture.id, "offset", 65535, "limit", 65536));
            assertThat(((McpSchema.TextContent) first.content().get(1)).text()
                            + ((McpSchema.TextContent) second.content().get(1)).text())
                    .isEqualTo(source);
            clearInvocations(fixture.executor);
            var oversized = fixture.call(Map.of("artifactId", fixture.id, "limit", 65537));
            assertThat(oversized.isError()).isTrue();
            verifyNoInteractions(fixture.executor);
        }
    }

    @Test
    void imageContentContainsExactValidatedBytesAndChecksAccessAgainBeforeDelivery() throws Exception {
        byte[] png = png();
        try (var fixture = new Fixture(png, "image/png")) {
            var result = fixture.call(Map.of("artifactId", fixture.id));
            assertThat(result.isError()).isFalse();
            var image = (McpSchema.ImageContent) result.content().get(1);
            assertThat(image.mimeType()).isEqualTo("image/png");
            assertThat(Base64.getDecoder().decode(image.data())).isEqualTo(png);
            verify(fixture.executor)
                    .readArtifact(
                            any(), any(), eq("artifact-session"), eq(fixture.id), eq((long) png.length), eq(1), any());
            assertThat(fixture.info(result).path("nextOffset").isNull()).isTrue();
        }
        try (var fixture = new Fixture(png, "image/png")) {
            fixture.expireAtEnd = true;
            var result = fixture.call(Map.of("artifactId", fixture.id));
            assertThat(result.isError()).isTrue();
            assertThat(result.content()).noneMatch(McpSchema.ImageContent.class::isInstance);
        }
    }

    @Test
    void unsupportedImageTypesReturnExactBinaryPagesWithoutRenderingActiveContent() throws Exception {
        try (var fixture = new Fixture("<svg onload='bad()'/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml")) {
            var result = fixture.call(Map.of("artifactId", fixture.id));
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).noneMatch(McpSchema.ImageContent.class::isInstance);
            var resource = (McpSchema.EmbeddedResource) result.content().get(1);
            var blob = (McpSchema.BlobResourceContents) resource.resource();
            assertThat(blob.mimeType()).isEqualTo("application/octet-stream");
            assertThat(Base64.getDecoder().decode(blob.blob()))
                    .isEqualTo("<svg onload='bad()'/>".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void invalidImageOrChangedDigestNeverReturnsAnImage() throws Exception {
        try (var fixture = new Fixture("not a PNG".getBytes(StandardCharsets.UTF_8), "image/png")) {
            var result = fixture.call(Map.of("artifactId", fixture.id));
            assertThat(result.isError()).isTrue();
            assertThat(result.content()).noneMatch(McpSchema.ImageContent.class::isInstance);
        }
        try (var fixture = new Fixture(png(), "image/png")) {
            fixture.digest = "0".repeat(64);
            var result = fixture.call(Map.of("artifactId", fixture.id));
            assertThat(result.isError()).isTrue();
            assertThat(result.content()).noneMatch(McpSchema.ImageContent.class::isInstance);
        }
    }

    @Test
    void textPagesRetainUtf8CharactersAcrossByteBoundariesAndOfferExactBinaryReads() throws Exception {
        String source = "a".repeat(8191) + "猫\r\nend";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        try (var fixture = new Fixture(bytes, "text/plain")) {
            var first = fixture.call(Map.of("artifactId", fixture.id));
            assertThat(first.isError()).isFalse();
            assertThat(fixture.info(first).path("nextOffset").longValue()).isEqualTo(8191);
            String left = ((McpSchema.TextContent) first.content().get(1)).text();
            var second = fixture.call(Map.of("artifactId", fixture.id, "offset", 8191));
            assertThat(second.isError()).isFalse();
            assertThat(left + ((McpSchema.TextContent) second.content().get(1)).text())
                    .isEqualTo(source);
            assertThat(fixture.info(second).path("nextOffset").isNull()).isTrue();
            var raw = fixture.call(Map.of("artifactId", fixture.id, "offset", 8191, "limit", 4, "format", "bytes"));
            var resource = (McpSchema.EmbeddedResource) raw.content().get(1);
            var blob = (McpSchema.BlobResourceContents) resource.resource();
            assertThat(Base64.getDecoder().decode(blob.blob())).isEqualTo(Arrays.copyOfRange(bytes, 8191, 8195));
            assertThat(blob.mimeType()).isEqualTo("application/octet-stream");
        }
    }

    @Test
    void otherFilesAreBoundedBinaryResourcesAndUnknownHandlesDoNotExposeContent() throws Exception {
        byte[] bytes = {0, -1, 2, 3, 4, 5, 6, 7};
        try (var fixture = new Fixture(bytes, "application/pdf")) {
            var result = fixture.call(Map.of("artifactId", fixture.id, "offset", 4, "limit", 4));
            assertThat(result.isError()).isFalse();
            var blob = (McpSchema.BlobResourceContents)
                    ((McpSchema.EmbeddedResource) result.content().get(1)).resource();
            assertThat(Base64.getDecoder().decode(blob.blob())).isEqualTo(new byte[] {4, 5, 6, 7});
            assertThat(blob.uri()).isEqualTo("poketto-artifact:///" + fixture.id + "?offset=4");
            var unknown = fixture.call(Map.of("artifactId", UUID.randomUUID().toString()));
            assertThat(unknown.isError()).isTrue();
            assertThat(fixture.info(unknown).path("code").stringValue()).isEqualTo("ARTIFACT_UNAVAILABLE");
            assertThat(unknown.content()).hasSize(1);
        }
    }

    private static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output))
                .isTrue();
        return output.toByteArray();
    }

    private static final class Fixture implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final ObjectMapper json = new ObjectMapper();
        final RepositoryExecutor executor = mock(RepositoryExecutor.class);
        final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        final ImageMemoryAdmission memory = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 1, Duration.ZERO);
        final ImageRequestScope scope =
                memory.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        final McpServerFeatures.SyncToolSpecification tool;
        String digest;
        boolean expireAtEnd;

        @SuppressWarnings("unchecked")
        Fixture(byte[] content, String type) throws Exception {
            digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            var provider = (ObjectProvider<RepositoryExecutor>) mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(executor);
            when(provider.getObject()).thenReturn(executor);
            var assets = (ObjectProvider<AssetService>) mock(ObjectProvider.class);
            var sessions = mock(McpSessions.class);
            when(sessions.resolve(any()))
                    .thenReturn(new McpSessions.Identity(mock(AuthPrincipal.class), WorkspaceId.random()));
            when(exchange.sessionId()).thenReturn("artifact-session");
            when(exchange.transportContext())
                    .thenReturn(McpTransportContext.create(Map.of(
                            ImageRequestScope.ATTRIBUTE, scope, McpCancellation.CONTEXT_KEY, new McpCancellation())));
            when(executor.readArtifact(any(), any(), anyString(), anyString(), anyLong(), anyInt(), any()))
                    .thenAnswer(call -> {
                        long offset = call.getArgument(4);
                        int limit = call.getArgument(5);
                        if (!id.equals(call.getArgument(3)) || expireAtEnd && offset == content.length) {
                            return Optional.empty();
                        }
                        byte[] part = Arrays.copyOfRange(
                                content, (int) offset, Math.min(content.length, (int) offset + limit));
                        return Optional.of(new RepositoryExecutor.ArtifactChunk(
                                id, "artifact.bin", type, content.length, digest, false, 299, offset, part));
                    });
            tool = new RepositoryMcpTools(sessions, null, assets, provider, json)
                    .specifications().stream()
                            .filter(value -> value.tool().name().equals("get_artifact"))
                            .findFirst()
                            .orElseThrow();
        }

        McpSchema.CallToolResult call(Map<String, Object> arguments) {
            return tool.callHandler().apply(exchange, new McpSchema.CallToolRequest("get_artifact", arguments));
        }

        JsonNode info(McpSchema.CallToolResult result) {
            return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
        }

        @Override
        public void close() {
            scope.responseComplete();
            assertThat(memory.reservedBytes()).isZero();
        }
    }
}
