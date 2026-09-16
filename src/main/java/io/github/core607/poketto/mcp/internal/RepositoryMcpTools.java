package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.AssetBytes;
import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.assets.ImageTransferException;
import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
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
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private final McpSessions sessions;
    private final AuthService auth;
    private final ObjectProvider<AssetService> assets;
    private final ObjectProvider<RepositoryExecutor> executors;
    private final ObjectMapper json;
    private final ObjectProvider<ImageTransfers> transfers;
    private final McpArtifactResults artifacts;

    RepositoryMcpTools(
            McpSessions sessions,
            AuthService auth,
            ObjectProvider<AssetService> assets,
            ObjectProvider<RepositoryExecutor> executors,
            ObjectMapper json,
            ObjectProvider<ImageTransfers> transfers) {
        this.sessions = sessions;
        this.auth = auth;
        this.assets = assets;
        this.executors = executors;
        this.json = json;
        this.transfers = transfers;
        this.artifacts = new McpArtifactResults(json);
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        if (assets.getIfAvailable() != null) {
            tools.add(getAssetTool());
            tools.add(putAssetTool());
        }
        if (executors.getIfAvailable() != null) {
            tools.add(discardTool());
            tools.add(getArtifactTool());
            tools.add(executeTool());
        }
        return List.copyOf(tools);
    }

    private McpServerFeatures.SyncToolSpecification getAssetTool() {
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
                                Map.of("kind", Map.of("const", "managed"), "assetId", text(36), "revision", text(64)),
                                List.of("kind", "assetId", "revision"))));
        return tool(
                "get_asset",
                "Read an authorized exact repository image or managed image revision as bounded MCP image content. Repository commit may be omitted to select main; the response returns the resolved source.",
                object(Map.of("source", source), List.of("source")),
                true,
                false,
                true,
                this::getAsset);
    }

    private McpServerFeatures.SyncToolSpecification putAssetTool() {
        return tool(
                "put_asset",
                "Import an image from url or a platform file reference (file), at most 16 MiB. If you hold a local file, use mode=upload with operationKey only; use your own Python/Shell to HTTP PUT raw bytes to uploadUrl with Content-Type application/octet-stream. GET the same URL to check a lost upload response. Grants expire after 15 minutes. Reuse operationKey for identical retries, including after obtaining a replacement grant. Returns assetId/revision; link using poketto media link, then save selected text and index. Uploading does not write Git or publish.",
                putAssetSchema(),
                false,
                false,
                true,
                this::putAsset);
    }

    private McpServerFeatures.SyncToolSpecification discardTool() {
        return tool(
                "repo_discard",
                "Discard the exact working copy and its unsaved work. Supply its copyId as expectedCopyId. Busy copies are refused. DISCARDED or ABSENT confirms the target is gone. Use new after the account's copy has been discarded. No command executes and remote Git commits are not undone. After an unconfirmed response, retry only the same ID. Requires current execution permission and ownership of that copy.",
                object(
                        Map.of(
                                "expectedCopyId",
                                Map.of(
                                        "type",
                                        "string",
                                        "maxLength",
                                        36,
                                        "pattern",
                                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")),
                        List.of("expectedCopyId")),
                false,
                true,
                true,
                this::discard);
    }

    private McpServerFeatures.SyncToolSpecification getArtifactTool() {
        return tool(
                "get_artifact",
                "Read an unexpired artifact from the account's current execution lease. A grant change or process restart can invalidate the handle; recreate it from the retained copy. Auto format renders validated images in full (up to 16 MiB), or pages text. Other files and format=bytes return exact binary pages. Byte offset and limit apply to pages; continue with nextOffset. Handles do not publish, save, or grant access to another session.",
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
                this::getArtifact);
    }

    private McpServerFeatures.SyncToolSpecification executeTool() {
        return tool(
                "repo_exec",
                "Use shell, Python, Git, file listings and search in an isolated repository copy. Set expectedCopyId=new to open your account's default copy, creating it only if absent; otherwise retain copyId across calls. Authorized clients of one account share the copy within the same workspace and reading scope. Transport closure does not discard it; use repo_discard for explicit removal. Reconnection is automatic; no generation or resume flag is required. Retention reports expiry. Use a read-only inspection command after an interrupted call: retention.lastInterruptedCommand identifies earlier work that may have partially completed. SESSION_REPLACED or EXECUTION_REFUSED means this command did not execute. EXECUTION_UNCONFIRMED means a command was attempted; retain its copyId and inspect the same copy before deciding whether to write again. Do not replay uncertain writes. Every command starts at the repository root; /tmp resets per command. Read root AGENTS.md and poketto --help. Full readers retain original history; public readers get the current public projection. Omit commit to use the current acknowledged baseline. Successful saves advance local HEAD and index; repo_exec.commit reports the installed Git baseline. Edits stay local until poketto save. CLI operations can store media and commit authorized selections. Use poketto artifact create FILE --type MIME with get_artifact; long-output handles expire and may be truncated.",
                object(
                        Map.of(
                                "expectedCopyId",
                                Map.of(
                                        "type",
                                        "string",
                                        "maxLength",
                                        36,
                                        "pattern",
                                        "^(new|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$"),
                                "command",
                                text(16384),
                                "commit",
                                nullableCommit(),
                                "timeoutSeconds",
                                Map.of("type", "integer", "minimum", 1, "maximum", 60)),
                        List.of("expectedCopyId", "command")),
                false,
                true,
                false,
                this::execute);
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> schema,
            boolean readOnly,
            boolean destructive,
            boolean idempotent,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> operation) {
        var builder = McpSchema.Tool.builder(name, schema)
                .description(description)
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(readOnly)
                        .destructiveHint(destructive)
                        .idempotentHint(idempotent)
                        .openWorldHint(name.equals("put_asset"))
                        .build())
                .meta(name.equals("put_asset") ? Map.of("openai/fileParams", List.of("file")) : Map.of());
        return new McpServerFeatures.SyncToolSpecification(
                builder.build(),
                (exchange, request) -> McpToolOutcomes.recorded(json, name, () -> {
                    try {
                        sessions.resolve(exchange);
                        var arguments = request.arguments();
                        if (arguments == null) {
                            throw new IllegalArgumentException();
                        }
                        return invoke(exchange, name, arguments, operation);
                    } catch (AuthException | SecurityException exception) {
                        return error("DENIED", "Current workspace capability is required.");
                    } catch (SessionReplacedException exception) {
                        return McpCopyAdmission.copyReplaced(json, exception);
                    } catch (ExecutionUnconfirmedException exception) {
                        return McpCopyAdmission.executionUnconfirmed(json, exception);
                    } catch (ExecutionAdmissionException exception) {
                        return name.equals("repo_discard")
                                ? McpCopyAdmission.discardRefused(json, exception)
                                : McpCopyAdmission.admissionRefused(json, exception);
                    } catch (RepositoryConflictException exception) {
                        return error("CONFLICT", "Read current files and base commit before retrying.");
                    } catch (RepositoryWriteAmbiguousException exception) {
                        return error("INDETERMINATE", "Re-read authoritative main; do not retry this write blindly.");
                    } catch (ImageTransferException exception) {
                        String reason = exception.reason().name();
                        return McpToolOutcomes.failure(
                                json,
                                exception.reason() == ImageTransferException.Reason.IMAGE_MEMORY_BUSY
                                        ? "UNAVAILABLE"
                                        : reason,
                                reason,
                                "Image transfer did not complete; retain the operationKey.");
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
                }));
    }

    private McpSchema.CallToolResult invoke(
            McpSyncServerExchange exchange,
            String name,
            Map<String, Object> arguments,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> operation) {
        if (exchange.transportContext().get(ImageRequestScope.ATTRIBUTE) instanceof ImageRequestScope scope) {
            try (var producer = scope.producer()) {
                return operation.apply(exchange, arguments);
            }
        }
        if (name.equals("get_asset") || name.equals("get_artifact")) {
            return McpToolOutcomes.failure(
                    json, "UNAVAILABLE", "IMAGE_MEMORY_UNAVAILABLE", "Image response memory admission is unavailable.");
        }
        return operation.apply(exchange, arguments);
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
        fields(input, Set.of("operationKey", "mode", "url", "file"));
        PutAssetInput request = json.convertValue(input, PutAssetInput.class);
        var identity = sessions.resolve(exchange);
        auth.authorize(identity.principal(), identity.workspace(), Capability.WRITE_PRIVATE);
        if (request.mode().equals("upload")) {
            return textResult(
                    transfers.getObject().prepare(identity.principal(), identity.workspace(), request.operationKey()));
        }
        return textResult(transfers
                .getObject()
                .importUrl(identity.principal(), identity.workspace(), request.operationKey(), request.downloadUrl()));
    }

    private static Map<String, Object> putAssetSchema() {
        return object(
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
                        "mode", Map.of("type", "string", "enum", List.of("import", "upload")),
                        "url", text(16384),
                        "file",
                                object(
                                        Map.of(
                                                "download_url",
                                                text(16384),
                                                "file_id",
                                                text(1024),
                                                "mime_type",
                                                text(255),
                                                "file_name",
                                                text(1024)),
                                        List.of("download_url", "file_id"))),
                List.of("operationKey"));
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
        McpArtifactResults.Pages pages = (at, size) -> executor.readArtifact(
                identity.principal(), identity.workspace(), exchange.sessionId(), id, at, size, cancellation);
        var found = pages.read(offset, limit);
        if (found.isEmpty()) {
            return error("ARTIFACT_UNAVAILABLE", "Artifact is unavailable in this execution session or has expired.");
        }
        var first = found.orElseThrow();
        if (format.equals("auto") && IMAGE_TYPES.contains(first.mediaType())) {
            if (offset != 0 || first.size() > ManagedBlobStore.MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException();
            }
            return artifacts.image(first, pages);
        }
        if (format.equals("auto") && first.mediaType().startsWith("text/")) {
            return artifacts.text(first, offset);
        }
        return artifacts.binary(first, id, offset);
    }

    private McpSchema.CallToolResult discard(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("expectedCopyId"));
        RepositoryExecutor.CopyRequest copy = McpCopyAdmission.copyRequest(input);
        var request = new RepositoryExecutor.DiscardRequest(copy.id());
        var identity = sessions.resolve(exchange);
        auth.authorize(identity.principal(), identity.workspace(), Capability.EXECUTE_REPOSITORY);
        return textResult(executors
                .getObject()
                .discard(identity.principal(), identity.workspace(), request, cancellation(exchange)));
    }

    private McpSchema.CallToolResult execute(McpSyncServerExchange exchange, Map<String, Object> input) {
        fields(input, Set.of("expectedCopyId", "command", "commit", "timeoutSeconds"));
        RepositoryExecutor.CopyRequest copy = McpCopyAdmission.copyRequest(input);
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
                        copy,
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
        return McpToolOutcomes.failure(json, code, code, message);
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
