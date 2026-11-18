package io.github.core607.poketto.mcp.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** MCP copy identity inputs and retained-execution outcomes. */
final class McpCopyAdmission {
    private McpCopyAdmission() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AdmissionRefusal(
            String code, String reason, boolean executed, boolean recoveryAvailable, String message, String copyId) {}

    static McpSchema.CallToolResult admissionRefused(ObjectMapper json, ExecutionAdmissionException exception) {
        var body = new AdmissionRefusal(
                "EXECUTION_REFUSED",
                exception.reason().name(),
                false,
                exception.recoveryAvailable(),
                exception.reason() == ExecutionAdmissionException.Reason.PUBLICATION_CHANGED
                        ? "This command did not execute. Published content changed after this public copy was opened. Its files are retained, but it cannot save or run against outdated publication. Use expectedCopyId=new for a current projection within your account quota. The old copy still counts toward quota; repo_discard with its copyId explicitly deletes its local work."
                        : "This command did not execute. Inspect the reason and retry a read-only command against the intended copy ID when available. Earlier interrupted commands may have partially completed; inspect local and remote state before retrying writes.",
                exception.copyId().orElse(null));
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    private record UnconfirmedExecution(
            String code,
            String copyId,
            long expiresAt,
            boolean mayHaveExecuted,
            boolean recoveryAvailable,
            String message) {}

    static McpSchema.CallToolResult executionUnconfirmed(ObjectMapper json, ExecutionUnconfirmedException exception) {
        var body = new UnconfirmedExecution(
                "EXECUTION_UNCONFIRMED",
                exception.copyId(),
                exception.retention().expiresAt(),
                true,
                exception.recoveryAvailable(),
                "This command may have partially completed, including remote writes. Do not replay it. Before expiry, use this exact copy ID with a read-only inspection command. Reconnection is automatic; inspect interruption and pending-save state with poketto status before continuing.");
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DiscardRefusal(String code, String reason, String message) {}

    static McpSchema.CallToolResult discardRefused(ObjectMapper json, ExecutionAdmissionException exception) {
        var body = new DiscardRefusal(
                "DISCARD_UNCONFIRMED",
                exception.reason().name(),
                "Discard completion was not confirmed. Retry only the same copy ID after checking the reason. Remote Git commits are not undone. This response does not confirm that retained state still exists.");
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }

    static RepositoryExecutor.CopyRequest copyRequest(Map<String, Object> input) {
        return new RepositoryExecutor.CopyRequest(copyId(input));
    }

    private static String copyId(Map<String, Object> input) {
        if (!(input.get("expectedCopyId") instanceof String id)) {
            throw new IllegalArgumentException("An explicit copy ID is required");
        }
        return RepositoryExecutor.requireCopyId(id);
    }
}
