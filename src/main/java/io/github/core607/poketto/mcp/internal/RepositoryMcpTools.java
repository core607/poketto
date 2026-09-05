package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.AssetBytes;
import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
    private static final int MAX_TEXT_RESULT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BASE64_LENGTH = ((ManagedBlobStore.MAX_UPLOAD_BYTES + 2) / 3) * 4;
    private final McpSessions sessions;
    private final AuthService auth;
    private final AuthorizedRepositoryReader reader;
    private final RepositoryPatchService patches;
    private final ObjectProvider<AssetService> assets;
    private final ObjectProvider<RepositoryExecutor> executors;
    private final ObjectMapper json;

    RepositoryMcpTools(
            McpSessions sessions,
            AuthService auth,
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches,
            ObjectProvider<AssetService> assets,
            ObjectProvider<RepositoryExecutor> executors,
            ObjectMapper json) {
        this.sessions = sessions;
        this.auth = auth;
        this.reader = reader;
        this.patches = patches;
        this.assets = assets;
        this.executors = executors;
        this.json = json;
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool(
                "get_file",
                "Read exact original UTF-8 text from authoritative Git. Omit commit for current main; preserve the returned opaque revision when editing. Missing paths return expectedAbsence=true.",
                object(Map.of("path", text(255), "commit", nullableCommit()), List.of("path")),
                true,
                false,
                true,
                this::getFile));
        Map<String, Object> change = object(
                Map.of(
                        "path",
                        text(255),
                        "expectedAbsence",
                        Map.of("type", "boolean"),
                        "expectedRevision",
                        nullableText(128),
                        "content",
                        nullableText(1024 * 1024)),
                List.of("path", "expectedAbsence", "content"));
        tools.add(tool(
                "repo_patch",
                "Atomically create, update, move or delete text. Supply exact baseCommit (null only for unborn main) and revision or expected absence per path. Move uses a deletion plus creation. Re-read conflicts; never blindly retry an indeterminate result.",
                object(
                        Map.of(
                                "baseCommit",
                                nullableCommit(),
                                "changes",
                                Map.of("type", "array", "minItems", 1, "maxItems", 64, "items", change)),
                        List.of("baseCommit", "changes")),
                false,
                true,
                false,
                this::patch));
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
                    "repo_exec",
                    "Run bounded Git, search, shell or Python in this MCP session's isolated repository copy. Omitted commit retains its pinned revision. Execution never writes repository authority.",
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
                    false,
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
                if (request.arguments() == null) throw new IllegalArgumentException();
                return operation.apply(exchange, request.arguments());
            } catch (AuthException | SecurityException exception) {
                return error("DENIED", "Current workspace capability is required.");
            } catch (RepositoryConflictException exception) {
                return error("CONFLICT", "Read current files and base commit before retrying.");
            } catch (RepositoryWriteAmbiguousException exception) {
                return error("INDETERMINATE", "Re-read authoritative main; do not retry this write blindly.");
            } catch (AssetStorageException exception) {
                return error(exception.reason().name(), "Image operation could not be completed.");
            } catch (IllegalArgumentException exception) {
                return error("INVALID_INPUT", "Use the documented bounded fields and server-issued revisions.");
            } catch (ContentRepositoryException exception) {
                return error("UNAVAILABLE", "Repository authority is unavailable; no success is confirmed.");
            } catch (RuntimeException exception) {
                return error(
                        "UNAVAILABLE",
                        "Operation could not be completed; verify authoritative state before retrying writes.");
            }
        });
    }

    private McpSchema.CallToolResult getFile(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("path", "commit"));
        var identity = sessions.resolve(exchange);
        var file = reader.getFile(
                identity.principal(),
                identity.workspace(),
                optionalText(input, "commit", 40),
                requiredText(input, "path", 255));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.path());
        result.put("commit", file.commit().orElse(null));
        result.put("revision", file.revision().map(DocumentRevision::value).orElse(null));
        result.put("source", file.source().orElse(null));
        result.put("expectedAbsence", file.expectedAbsence());
        result.put("diagnostics", file.diagnostics());
        return textResult(result);
    }

    private McpSchema.CallToolResult patch(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("baseCommit", "changes"));
        if (!input.containsKey("baseCommit")
                || !(input.get("changes") instanceof List<?> changes)
                || changes.isEmpty()
                || changes.size() > 64) throw new IllegalArgumentException();
        List<RepositoryTextChange> edits = new ArrayList<>();
        for (Object value : changes) {
            Map<String, Object> change = mapping(value);
            fields(change, Set.of("path", "expectedAbsence", "expectedRevision", "content"));
            if (!(change.get("expectedAbsence") instanceof Boolean absence) || !change.containsKey("content"))
                throw new IllegalArgumentException();
            edits.add(new RepositoryTextChange(
                    requiredText(change, "path", 255),
                    absence,
                    optionalText(change, "expectedRevision", 128).map(DocumentRevision::new),
                    optionalText(change, "content", 1024 * 1024)));
        }
        var identity = sessions.resolve(exchange);
        var result = patches.apply(
                identity.principal(),
                identity.workspace(),
                new RepositoryPatch(optionalText(input, "baseCommit", 40), edits));
        Map<String, Object> revisions = new LinkedHashMap<>();
        result.revisions()
                .forEach((path, revision) -> revisions.put(
                        path, revision.map(DocumentRevision::value).orElse(null)));
        return textResult(Map.of(
                "commit",
                result.commit(),
                "committed",
                result.committed(),
                "snapshotUpdated",
                result.snapshotUpdated(),
                "revisions",
                revisions));
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
            if (!source.get("kind").equals("managed")) throw new IllegalArgumentException();
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
        if (base64.length() > MAX_BASE64_LENGTH) throw new IllegalArgumentException();
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
        if (bytes.length > ManagedBlobStore.MAX_UPLOAD_BYTES) throw new IllegalArgumentException();
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

    private McpSchema.CallToolResult execute(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("command", "commit", "timeoutSeconds"));
        var identity = sessions.resolve(exchange);
        auth.authorize(identity.principal(), identity.workspace(), Capability.EXECUTE_REPOSITORY);
        int timeout = 30;
        if (input.containsKey("timeoutSeconds")) {
            if (!(input.get("timeoutSeconds") instanceof Number number) || number.doubleValue() != number.intValue())
                throw new IllegalArgumentException();
            timeout = number.intValue();
        }
        if (timeout < 1 || timeout > 60) throw new IllegalArgumentException();
        var result = executors
                .getObject()
                .execute(
                        identity.principal(),
                        identity.workspace(),
                        exchange.sessionId(),
                        optionalText(input, "commit", 40),
                        requiredText(input, "command", 16384),
                        Duration.ofSeconds(timeout));
        return textResult(result);
    }

    private McpSchema.CallToolResult textResult(Object value) {
        String encoded = json.writeValueAsString(value);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_RESULT_BYTES)
            return error("OUTPUT_LIMIT", "Result exceeds the MCP text response bound.");
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

    private static Map<String, Object> nullableText(int maximum) {
        return Map.of("type", List.of("string", "null"), "maxLength", maximum);
    }

    private static Map<String, Object> nullableCommit() {
        return Map.of("type", List.of("string", "null"), "maxLength", 40, "pattern", "^[0-9a-f]{40}$");
    }

    private static void fields(Map<String, Object> values, Set<String> expected) {
        if (!expected.containsAll(values.keySet())) throw new IllegalArgumentException();
    }

    private static String requiredText(Map<String, Object> values, String field, int maximum) {
        return optionalText(values, field, maximum).orElseThrow(IllegalArgumentException::new);
    }

    private static Optional<String> optionalText(Map<String, Object> values, String field, int maximum) {
        Object value = values.get(field);
        if (value == null) return Optional.empty();
        if (!(value instanceof String text) || text.length() > maximum) throw new IllegalArgumentException();
        return Optional.of(text);
    }

    private static Map<String, Object> mapping(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw new IllegalArgumentException();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }
}
