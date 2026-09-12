package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.AssetBytes;
import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImagePreviewPolicy;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** Protocol mapping only: all repository, image and execution operations call shared authorized services. */
final class RepositoryMcpTools {
    /** Largest text result one tool may return before it is refused as an output-limit failure. */
    private static final int MAX_TEXT_RESULT_BYTES = 8 * 1024 * 1024;

    private static final int MAX_BASE64_LENGTH = ((ManagedBlobStore.MAX_UPLOAD_BYTES + 2) / 3) * 4;
    private final McpSessions sessions;
    private final AuthService auth;
    private final ObjectProvider<AssetService> assets;
    private final ObjectProvider<RepositoryExecutor> executors;
    private final ObjectMapper json;

    RepositoryMcpTools(
            McpSessions sessions,
            AuthService auth,
            ObjectProvider<AssetService> assets,
            ObjectProvider<RepositoryExecutor> executors,
            ObjectMapper json) {
        this.sessions = sessions;
        this.auth = auth;
        this.assets = assets;
        this.executors = executors;
        this.json = json;
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        if (assets.getIfAvailable() != null) {
            Map<String, Object> source = Map.of(
                    "oneOf",
                    List.of(
                            object(
                                    Map.of(
                                            "kind",
                                            Map.of("const", "repository"),
                                            "commit",
                                            nullableCommit(),
                                            "path",
                                            text(255)),
                                    List.of("kind", "path")),
                            object(
                                    Map.of(
                                            "kind",
                                            Map.of("const", "managed"),
                                            "assetId",
                                            text(36),
                                            "revision",
                                            text(64)),
                                    List.of("kind", "assetId", "revision"))));
            tools.add(tool(
                    "get_asset",
                    "Read an authorized exact repository image or managed image revision as bounded MCP image content. Repository commit may be omitted to select main; the response returns the resolved source.",
                    object(Map.of("source", source), List.of("source")),
                    true,
                    false,
                    true,
                    this::getAsset));
            tools.add(tool(
                    "put_asset",
                    "Upload original image bytes as standard base64, at most 16 MiB decoded. Reuse the same operationKey for identical retries. Returns an immutable managed reference; does not write Git or publish.",
                    object(
                            Map.of(
                                    "operationKey",
                                    Map.of(
                                            "type",
                                            "string",
                                            "minLength",
                                            16,
                                            "maxLength",
                                            128,
                                            "pattern",
                                            "^[A-Za-z0-9_-]+$"),
                                    "base64",
                                    text(MAX_BASE64_LENGTH)),
                            List.of("operationKey", "base64")),
                    false,
                    false,
                    true,
                    this::putAsset));
        }
        if (executors.getIfAvailable() != null) {
            tools.add(tool(
                    "get_artifact",
                    "Read an unexpired artifact created by this MCP execution session. Auto format renders validated images in full (up to 16 MiB), or pages text. Other files and format=bytes return exact binary pages. Byte offset and limit apply to pages; continue with nextOffset. Handles do not publish, save, or grant access to another session.",
                    object(
                            Map.of(
                                    "artifactId",
                                    text(36),
                                    "offset",
                                    Map.of("type", "integer", "minimum", 0, "maximum", 134217728),
                                    "limit",
                                    Map.of("type", "integer", "minimum", 4, "maximum", 65536),
                                    "format",
                                    Map.of("type", "string", "enum", List.of("auto", "bytes"))),
                            List.of("artifactId")),
                    true,
                    false,
                    true,
                    this::getArtifact));
            tools.add(tool(
                    "repo_exec",
                    "Use shell, Python, Git, file listings and search as the main file entrance in this MCP session's isolated repository copy. Every command starts in the repository root; /tmp is reset before each command. Read the root AGENTS.md when present and use poketto --help for host operations. Full readers retain original history; public readers get only the current public projection. Omitted commit retains the session copy. File edits stay local until poketto save; authorized CLI operations can store media and commit selected changes to repository authority. Use poketto artifact create FILE --type MIME to return files through get_artifact. Long output includes artifact handles; inspect their truncated flags and read needed pages before they expire.",
                    object(
                            Map.of(
                                    "command",
                                    text(16384),
                                    "commit",
                                    nullableCommit(),
                                    "timeoutSeconds",
                                    Map.of("type", "integer", "minimum", 1, "maximum", 60)),
                            List.of("command")),
                    false,
                    true,
                    false,
                    this::execute));
        }
        return List.copyOf(tools);
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> schema,
            boolean readOnly,
            boolean destructive,
            boolean idempotent,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> operation) {
        var tool = McpSchema.Tool.builder(name, schema)
                .description(description)
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(readOnly)
                        .destructiveHint(destructive)
                        .idempotentHint(idempotent)
                        .openWorldHint(false)
                        .build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                sessions.resolve(exchange);
                var arguments = request.arguments();
                if (arguments == null) {
                    throw new IllegalArgumentException();
                }
                if (exchange.transportContext().get(ImageRequestScope.ATTRIBUTE) instanceof ImageRequestScope scope) {
                    try (var producer = scope.producer()) {
                        return operation.apply(exchange, arguments);
                    }
                }
                if (name.equals("get_asset") || name.equals("put_asset") || name.equals("get_artifact")) {
                    return error("UNAVAILABLE", "Image memory admission is unavailable.");
                }
                return operation.apply(exchange, arguments);
            } catch (AuthException | SecurityException exception) {
                return error("DENIED", "Current workspace capability is required.");
            } catch (RepositoryConflictException exception) {
                return error("CONFLICT", "Read current files and base commit before retrying.");
            } catch (RepositoryWriteAmbiguousException exception) {
                return error("INDETERMINATE", "Re-read authoritative main; do not retry this write blindly.");
            } catch (AssetStorageException exception) {
                return error(exception.reason().name(), "Image operation could not be completed.");
            } catch (IllegalArgumentException exception) {
                return error("INVALID_INPUT", "Use the documented bounded fields.");
            } catch (ContentRepositoryException exception) {
                return error("UNAVAILABLE", "Repository authority is unavailable; no success is confirmed.");
            } catch (RuntimeException exception) {
                return error(
                        "UNAVAILABLE",
                        "Operation could not be completed; verify authoritative state before retrying writes.");
            }
        });
    }

    private static int boundedInteger(Map<String, Object> input, String field, int fallback, int minimum, int maximum) {
        if (!input.containsKey(field)) {
            return fallback;
        }
        if (!(input.get(field) instanceof Number number)
                || number.doubleValue() != number.intValue()
                || number.intValue() < minimum
                || number.intValue() > maximum) {
            throw new IllegalArgumentException();
        }
        return number.intValue();
    }

    private McpSchema.CallToolResult getAsset(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("source"));
        Map<String, Object> source = mapping(input.get("source"));
        AssetSource selected;
        if (requiredText(source, "kind", 16).equals("repository")) {
            fields(source, Set.of("kind", "path", "commit"));
            selected =
                    new AssetSource.Repository(optionalText(source, "commit", 40), requiredText(source, "path", 255));
        } else {
            fields(source, Set.of("kind", "assetId", "revision"));
            if (!source.get("kind").equals("managed")) {
                throw new IllegalArgumentException();
            }
            selected = new AssetSource.Managed(new ManagedAssetReference(
                    UUID.fromString(requiredText(source, "assetId", 36)), requiredText(source, "revision", 64)));
        }
        var identity = sessions.resolve(exchange);
        AssetBytes result = assets.getObject().readExact(identity.principal(), identity.workspace(), selected);
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (result.source() instanceof AssetSource.Repository git) {
            resolved.put("kind", "repository");
            resolved.put("path", git.path());
            resolved.put("commit", git.commit().orElseThrow());
        } else {
            var managed = (AssetSource.Managed) result.source();
            resolved.put("kind", "managed");
            resolved.put("assetId", managed.reference().assetId());
            resolved.put("revision", managed.reference().revision());
        }
        String metadata = json.writeValueAsString(Map.of(
                "source",
                resolved,
                "revision",
                result.revision(),
                "mediaType",
                result.mediaType(),
                "size",
                result.size()));
        String base64 = Base64.getEncoder().encodeToString(result.bytes());
        if (base64.length() > MAX_BASE64_LENGTH) {
            throw new IllegalArgumentException();
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(metadata)
                .addContent(McpSchema.ImageContent.builder(base64, result.mediaType())
                        .build())
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult putAsset(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("operationKey", "base64"));
        var identity = sessions.resolve(exchange);
        auth.authorize(identity.principal(), identity.workspace(), Capability.WRITE_PRIVATE);
        byte[] bytes = Base64.getDecoder().decode(requiredText(input, "base64", MAX_BASE64_LENGTH));
        if (bytes.length > ManagedBlobStore.MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException();
        }
        var result = assets.getObject()
                .upload(
                        identity.principal(),
                        identity.workspace(),
                        requiredText(input, "operationKey", 128),
                        new ByteArrayInputStream(bytes));
        return textResult(Map.of(
                "assetId",
                result.reference().assetId(),
                "revision",
                result.reference().revision(),
                "reference",
                result.reference().toString(),
                "mediaType",
                result.mediaType(),
                "size",
                result.size()));
    }

    private McpSchema.CallToolResult getArtifact(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("artifactId", "offset", "limit", "format"));
        String id = requiredText(input, "artifactId", 36);
        int offset = boundedInteger(input, "offset", 0, 0, 134217728);
        int limit = boundedInteger(input, "limit", 8192, 4, 65536);
        String format = optionalText(input, "format", 5).orElse("auto");
        if (!Set.of("auto", "bytes").contains(format)) {
            throw new IllegalArgumentException();
        }
        var identity = sessions.resolve(exchange);
        var cancellation = cancellation(exchange);
        var executor = executors.getObject();
        var found = executor.readArtifact(
                identity.principal(), identity.workspace(), exchange.sessionId(), id, offset, limit, cancellation);
        if (found.isEmpty()) {
            return error("ARTIFACT_UNAVAILABLE", "Artifact is unavailable in this execution session or has expired.");
        }
        var first = found.orElseThrow();
        if (format.equals("auto")
                && Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(first.mediaType())) {
            if (offset != 0 || first.size() > ManagedBlobStore.MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException();
            }
            byte[] content = new byte[Math.toIntExact(first.size())];
            byte[] initial = first.bytes();
            System.arraycopy(initial, 0, content, 0, initial.length);
            int received = initial.length;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (received < content.length) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Artifact transfer deadline exceeded");
                }
                var next = executor.readArtifact(
                                identity.principal(),
                                identity.workspace(),
                                exchange.sessionId(),
                                id,
                                received,
                                Math.min(65536, content.length - received),
                                cancellation)
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
            String digest;
            try {
                digest = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException(unavailable);
            }
            if (!digest.equals(first.sha256())
                    || !ImagePreviewPolicy.validate(content).equals(first.mediaType())) {
                throw new IllegalStateException("Artifact image is invalid");
            }
            var confirmed = executor.readArtifact(
                            identity.principal(),
                            identity.workspace(),
                            exchange.sessionId(),
                            id,
                            first.size(),
                            1,
                            cancellation)
                    .orElseThrow(() -> new IllegalStateException("Artifact expired before delivery"));
            if (!confirmed.sha256().equals(first.sha256()) || confirmed.size() != first.size()) {
                throw new IllegalStateException("Artifact changed before delivery");
            }
            var metadata = artifactInfo(first, first.size());
            metadata.put("expiresInSeconds", confirmed.expiresInSeconds());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(metadata))
                    .addContent(McpSchema.ImageContent.builder(
                                    Base64.getEncoder().encodeToString(content), first.mediaType())
                            .build())
                    .isError(false)
                    .build();
        }
        byte[] bytes = first.bytes();
        if (format.equals("auto") && first.mediaType().startsWith("text/")) {
            var source = ByteBuffer.wrap(bytes);
            var target = CharBuffer.allocate(bytes.length);
            var decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            decoder.decode(source, target, offset + bytes.length == first.size());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json.writeValueAsString(artifactInfo(first, offset + source.position())))
                    .addTextContent(target.flip().toString())
                    .isError(false)
                    .build();
        }
        var resource = new McpSchema.BlobResourceContents(
                "poketto-artifact:///" + id + "?offset=" + offset,
                "application/octet-stream",
                Base64.getEncoder().encodeToString(bytes));
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(artifactInfo(first, offset + bytes.length)))
                .addContent(McpSchema.EmbeddedResource.builder(resource).build())
                .isError(false)
                .build();
    }

    private static Map<String, Object> artifactInfo(RepositoryExecutor.ArtifactChunk chunk, long next) {
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

    private McpSchema.CallToolResult execute(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("command", "commit", "timeoutSeconds"));
        var identity = sessions.resolve(exchange);
        auth.authorize(identity.principal(), identity.workspace(), Capability.EXECUTE_REPOSITORY);
        int timeout = 30;
        if (input.containsKey("timeoutSeconds")) {
            if (!(input.get("timeoutSeconds") instanceof Number number) || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException();
            }
            timeout = number.intValue();
        }
        if (timeout < 1 || timeout > 60) {
            throw new IllegalArgumentException();
        }
        var result = executors
                .getObject()
                .execute(
                        identity.principal(),
                        identity.workspace(),
                        exchange.sessionId(),
                        optionalText(input, "commit", 40),
                        requiredText(input, "command", 16384),
                        Duration.ofSeconds(timeout),
                        cancellation(exchange));
        return textResult(result);
    }

    private static McpCancellation cancellation(McpSyncServerExchange exchange) {
        if (!(exchange.transportContext().get(McpCancellation.CONTEXT_KEY) instanceof McpCancellation cancellation)) {
            throw new SecurityException("Server execution cancellation context required");
        }
        return cancellation;
    }

    private McpSchema.CallToolResult textResult(Object value) {
        String encoded = json.writeValueAsString(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_RESULT_BYTES) {
            return error("OUTPUT_LIMIT", "Result exceeds the MCP text response bound.");
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(encoded)
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult error(String code, String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(Map.of("code", code, "message", message)))
                .isError(true)
                .build();
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    private static Map<String, Object> nullableCommit() {
        return Map.of("type", List.of("string", "null"), "maxLength", 40, "pattern", "^[0-9a-f]{40}$");
    }

    private static void fields(Map<String, Object> values, Set<String> expected) {
        if (!expected.containsAll(values.keySet())) {
            throw new IllegalArgumentException();
        }
    }

    private static String requiredText(Map<String, Object> values, String field, int maximum) {
        return optionalText(values, field, maximum).orElseThrow(IllegalArgumentException::new);
    }

    private static Optional<String> optionalText(Map<String, Object> values, String field, int maximum) {
        Object value = values.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text) || text.length() > maximum) {
            throw new IllegalArgumentException();
        }
        return Optional.of(text);
    }

    private static Map<String, Object> mapping(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }
}
