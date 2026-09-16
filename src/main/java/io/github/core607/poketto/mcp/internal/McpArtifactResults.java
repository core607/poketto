package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.ImagePreviewPolicy;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Renders one resolved artifact page as image, text or binary tool content. */
final class McpArtifactResults {
    private final ObjectMapper json;

    McpArtifactResults(ObjectMapper json) {
        this.json = json;
    }

    /** Reads one bounded page of the artifact the caller already resolved. */
    interface Pages {
        Optional<RepositoryExecutor.ArtifactChunk> read(long offset, int limit);
    }

    // A whole image is delivered only after every page, the digest and the media type agree with the
    // first page, and the artifact still exists.
    McpSchema.CallToolResult image(RepositoryExecutor.ArtifactChunk first, Pages pages) {
        byte[] content = assemble(first, pages);
        if (!sha256(content).equals(first.sha256())
                || !ImagePreviewPolicy.validate(content).equals(first.mediaType())) {
            throw new IllegalStateException("Artifact image is invalid");
        }
        RepositoryExecutor.ArtifactChunk confirmed = pages.read(first.size(), 1)
                .orElseThrow(() -> new IllegalStateException("Artifact expired before delivery"));
        if (!confirmed.sha256().equals(first.sha256()) || confirmed.size() != first.size()) {
            throw new IllegalStateException("Artifact changed before delivery");
        }
        var metadata = info(first, first.size());
        metadata.put("expiresInSeconds", confirmed.expiresInSeconds());
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(metadata))
                .addContent(
                        McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(content), first.mediaType())
                                .build())
                .isError(false)
                .build();
    }

    private static byte[] assemble(RepositoryExecutor.ArtifactChunk first, Pages pages) {
        byte[] content = new byte[Math.toIntExact(first.size())];
        byte[] initial = first.bytes();
        System.arraycopy(initial, 0, content, 0, initial.length);
        int received = initial.length;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (received < content.length) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Artifact transfer deadline exceeded");
            }
            RepositoryExecutor.ArtifactChunk next = pages.read(received, Math.min(65536, content.length - received))
                    .orElseThrow(() -> new IllegalStateException("Artifact expired during transfer"));
            if (!next.artifactId().equals(first.artifactId())
                    || next.size() != first.size()
                    || !next.sha256().equals(first.sha256())
                    || !next.mediaType().equals(first.mediaType())
                    || next.offset() != received) {
                throw new IllegalStateException("Artifact changed during transfer");
            }
            byte[] part = next.bytes();
            if (part.length < 1 || part.length > content.length - received) {
                throw new IllegalStateException("Invalid artifact chunk");
            }
            System.arraycopy(part, 0, content, received, part.length);
            received += part.length;
        }
        return content;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    McpSchema.CallToolResult text(RepositoryExecutor.ArtifactChunk chunk, int offset) {
        byte[] bytes = chunk.bytes();
        var source = ByteBuffer.wrap(bytes);
        var target = CharBuffer.allocate(bytes.length);
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        decoder.decode(source, target, offset + bytes.length == chunk.size());
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(info(chunk, offset + source.position())))
                .addTextContent(target.flip().toString())
                .isError(false)
                .build();
    }

    McpSchema.CallToolResult binary(RepositoryExecutor.ArtifactChunk chunk, String id, int offset) {
        byte[] bytes = chunk.bytes();
        var resource = new McpSchema.BlobResourceContents(
                "poketto-artifact:///" + id + "?offset=" + offset,
                "application/octet-stream",
                Base64.getEncoder().encodeToString(bytes));
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(info(chunk, offset + bytes.length)))
                .addContent(McpSchema.EmbeddedResource.builder(resource).build())
                .isError(false)
                .build();
    }

    static Map<String, Object> info(RepositoryExecutor.ArtifactChunk chunk, long next) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactId", chunk.artifactId());
        result.put("name", chunk.name());
        result.put("mediaType", chunk.mediaType());
        result.put("size", chunk.size());
        result.put("sha256", chunk.sha256());
        result.put("truncated", chunk.truncated());
        result.put("expiresInSeconds", chunk.expiresInSeconds());
        result.put("offset", chunk.offset());
        result.put("nextOffset", next < chunk.size() ? next : null);
        return result;
    }
}
