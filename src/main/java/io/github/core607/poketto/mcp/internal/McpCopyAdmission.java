package io.github.core607.poketto.mcp.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** MCP copy identity inputs and pre-execution refusals. */
final class McpCopyAdmission {
    private McpCopyAdmission() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CopyReplacement(
            String code,
            String reason,
            boolean executed,
            boolean recoveryAvailable,
            boolean newCopyAllowed,
            String message,
            String copyId) {}

    static McpSchema.CallToolResult copyReplaced(ObjectMapper json, SessionReplacedException exception) {
        String message =
                switch (exception.reason()) {
                    case MISSING_COPY ->
                        "Expected copy is unavailable in this MCP session; this command did not execute. Earlier unsaved work may be lost. Use expectedCopyId=new only to intentionally start fresh; do not replay an uncertain write.";
                    case DIFFERENT_COPY ->
                        "Expected copy ID does not match this MCP session; this command did not execute. Use the available copyId only if you intend that copy. Do not assume earlier edits survived or replay an uncertain write.";
                    case CLOSED_COPY ->
                        exception.newCopyAllowed()
                                ? "This copy has closed and its lease is released; this command did not execute. Unsaved work may be lost. Use expectedCopyId=new only to intentionally start fresh; do not replay an uncertain write."
                                : "This copy is closing or its lease release is unconfirmed; this command did not execute. New admission remains unavailable until command exit and lease release are confirmed. Do not replay an uncertain write.";
                };
        var body = new CopyReplacement(
                "SESSION_REPLACED",
                exception.reason().name(),
                false,
                false,
                exception.newCopyAllowed(),
                message,
                exception.currentCopyId().orElse(null));
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AdmissionRefusal(
            String code,
            String reason,
            boolean executed,
            Long currentGeneration,
            boolean recoveryAvailable,
            String message) {}

    static McpSchema.CallToolResult admissionRefused(ObjectMapper json, ExecutionAdmissionException exception) {
        var body = new AdmissionRefusal(
                "EXECUTION_REFUSED",
                exception.reason().name(),
                false,
                exception.currentGeneration(),
                exception.recoveryAvailable(),
                "This command did not execute. Recovery requires the intended copy ID, its current generation and resume=true. Earlier interrupted commands may have partially completed; inspect recovered state before retrying writes.");
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DiscardRefusal(String code, String reason, Long currentGeneration, String message) {}

    static McpSchema.CallToolResult discardRefused(ObjectMapper json, ExecutionAdmissionException exception) {
        var body = new DiscardRefusal(
                "DISCARD_UNCONFIRMED",
                exception.reason().name(),
                exception.currentGeneration(),
                "Discard completion was not confirmed. Retry only the same copy ID and expected generation after checking the reason. Remote Git commits are not undone. This response does not confirm that retained state still exists.");
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    static RepositoryExecutor.CopyRequest copyRequest(Map<String, Object> input) {
        Long generation = null;
        if (input.containsKey("expectedGeneration")) {
            Object value = input.get("expectedGeneration");
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("Expected generation must be an integer");
            }
            if (number.doubleValue() != number.longValue()) {
                throw new IllegalArgumentException("Expected generation must be an integer");
            }
            generation = number.longValue();
        }
        Object resume = input.getOrDefault("resume", false);
        if (!(resume instanceof Boolean recover)) {
            throw new IllegalArgumentException("Resume must be a boolean");
        }
        return new RepositoryExecutor.CopyRequest(copyId(input), generation, recover);
    }

    private static String copyId(Map<String, Object> input) {
        if (!(input.get("expectedCopyId") instanceof String id)) {
            throw new IllegalArgumentException("An explicit copy ID is required");
        }
        return RepositoryExecutor.requireCopyId(id);
    }
}
