package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpDiscardTests {
    private final RepositoryExecutor executor = mock(RepositoryExecutor.class);
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final AuthService auth = mock(AuthService.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final ObjectMapper json = new ObjectMapper();
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final McpServerFeatures.SyncToolSpecification tool;

    McpDiscardTests() {
        var sessions = mock(McpSessions.class);
        when(sessions.resolve(exchange)).thenReturn(new McpSessions.Identity(principal, workspace));
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(McpCancellation.CONTEXT_KEY, new McpCancellation())));
        var beans = new StaticListableBeanFactory(Map.of("executor", executor));
        tool = new RepositoryMcpTools(
                        sessions,
                        auth,
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        json)
                .specifications().stream()
                        .filter(value -> value.tool().name().equals("repo_discard"))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void destructiveToolRequiresAnExactCopyAndValidOptionalGeneration() {
        var schema = json.valueToTree(tool.tool().inputSchema());
        assertThat(schema.path("required").toString()).isEqualTo("[\"expectedCopyId\"]");
        assertThat(tool.tool().annotations().destructiveHint()).isTrue();
        String id = UUID.randomUUID().toString();
        for (var input : List.of(
                Map.<String, Object>of("expectedCopyId", "new"),
                Map.<String, Object>of("expectedCopyId", "new", "expectedGeneration", 1),
                Map.<String, Object>of("expectedCopyId", id, "expectedGeneration", 1.5),
                Map.<String, Object>of("expectedCopyId", id, "expectedGeneration", 0),
                Map.<String, Object>of(
                        "expectedCopyId", id, "expectedGeneration", RepositoryExecutor.MAX_GENERATION + 1),
                Map.<String, Object>of("expectedCopyId", id, "expectedGeneration", 1, "resume", true))) {
            assertThat(body(call(input)).path("code").asString()).isEqualTo("INVALID_INPUT");
        }
        verifyNoInteractions(executor);
    }

    @Test
    void forwardsOnlyTheServerIdentityAndExactDeletionTarget() {
        String id = UUID.randomUUID().toString();
        var request = new RepositoryExecutor.DiscardRequest(id, 3L);
        when(executor.discard(eq(principal), eq(workspace), eq(request), any()))
                .thenReturn(new RepositoryExecutor.DiscardResult(id, RepositoryExecutor.DiscardStatus.DISCARDED));
        var result = call(Map.of("expectedCopyId", id, "expectedGeneration", 3));
        assertThat(result.isError()).isFalse();
        assertThat(body(result).path("status").asString()).isEqualTo("DISCARDED");
        assertThat(body(result).path("copyId").asString()).isEqualTo(id);
    }

    @Test
    void forwardsNonRetainedDiscardWithoutInventingAGeneration() {
        String id = UUID.randomUUID().toString();
        var request = new RepositoryExecutor.DiscardRequest(id, null);
        when(executor.discard(eq(principal), eq(workspace), eq(request), any()))
                .thenReturn(new RepositoryExecutor.DiscardResult(id, RepositoryExecutor.DiscardStatus.DISCARDED));
        var result = call(Map.of("expectedCopyId", id));
        assertThat(result.isError()).isFalse();
        assertThat(body(result).path("status").asString()).isEqualTo("DISCARDED");
    }

    @Test
    void revokedExecutionCapabilityNeverReachesDeletion() {
        when(auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY))
                .thenThrow(new AuthException(AuthException.Code.DENIED));
        var result = call(Map.of("expectedCopyId", UUID.randomUUID().toString(), "expectedGeneration", 1));
        assertThat(body(result).path("code").asString()).isEqualTo("DENIED");
        verifyNoInteractions(executor);
    }

    @Test
    void uncertainDeletionDoesNotClaimThatNoMutationExecuted() {
        when(executor.discard(any(), any(), any(), any()))
                .thenThrow(
                        new ExecutionAdmissionException(ExecutionAdmissionException.Reason.UNAVAILABLE, null, false));
        var result = call(Map.of("expectedCopyId", UUID.randomUUID().toString(), "expectedGeneration", 1));
        assertThat(result.isError()).isTrue();
        assertThat(body(result).path("code").asString()).isEqualTo("DISCARD_UNCONFIRMED");
        assertThat(body(result).has("executed")).isFalse();
        assertThat(body(result).path("message").asString())
                .contains("does not confirm that retained state still exists");
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return tool.callHandler().apply(exchange, new McpSchema.CallToolRequest("repo_discard", arguments));
    }

    private JsonNode body(McpSchema.CallToolResult result) {
        return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }
}
