package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpCopyIdentityTests {
    private final RepositoryExecutor executor = mock(RepositoryExecutor.class);
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final ObjectMapper json = new ObjectMapper();
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final McpServerFeatures.SyncToolSpecification tool;

    McpCopyIdentityTests() {
        var sessions = mock(McpSessions.class);
        when(sessions.resolve(exchange)).thenReturn(new McpSessions.Identity(principal, workspace));
        when(exchange.sessionId()).thenReturn("transport");
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(McpCancellation.CONTEXT_KEY, new McpCancellation())));
        var beans = new StaticListableBeanFactory(Map.of("executor", executor));
        tool = new RepositoryMcpTools(
                        sessions,
                        mock(AuthService.class),
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        json)
                .specifications().stream()
                        .filter(value -> value.tool().name().equals("repo_exec"))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void schemaAndHandlerRequireAnExplicitCopyBeforeCallingTheExecutor() {
        var schema = json.valueToTree(tool.tool().inputSchema());
        assertThat(schema.path("required").toString()).contains("expectedCopyId", "command");
        for (var arguments : java.util.List.of(
                Map.<String, Object>of("command", "pwd"),
                Map.<String, Object>of("command", "pwd", "expectedCopyId", "bad-copy"))) {
            var result = call(arguments);
            assertThat(result.isError()).isTrue();
            assertThat(body(result).path("code").stringValue()).isEqualTo("INVALID_INPUT");
        }
        verifyNoInteractions(executor);
    }

    @Test
    void aNormalNonzeroResultReturnsTheAcknowledgedIdAndForwardsTheExpectedIdUnchanged() {
        String id = UUID.randomUUID().toString();
        when(executor.execute(
                        eq(principal),
                        eq(workspace),
                        eq("transport"),
                        eq("new"),
                        eq(Optional.empty()),
                        eq("pwd"),
                        eq(Duration.ofSeconds(30)),
                        any()))
                .thenReturn(new RepositoryExecutor.ExecutionResult(
                        id,
                        "a".repeat(40),
                        0,
                        "repository",
                        "",
                        false,
                        false,
                        false,
                        RepositoryExecutor.TerminationReason.NORMAL,
                        Map.of(),
                        Map.of()));
        var initial = call(Map.of("expectedCopyId", "new", "command", "pwd"));
        assertThat(initial.isError()).isFalse();
        assertThat(body(initial).path("copyId").stringValue()).isEqualTo(id);
        when(executor.execute(
                        eq(principal),
                        eq(workspace),
                        eq("transport"),
                        eq(id),
                        eq(Optional.empty()),
                        eq("false"),
                        eq(Duration.ofSeconds(30)),
                        any()))
                .thenReturn(new RepositoryExecutor.ExecutionResult(
                        id,
                        "a".repeat(40),
                        1,
                        "",
                        "failed command",
                        false,
                        false,
                        false,
                        RepositoryExecutor.TerminationReason.NORMAL,
                        Map.of(),
                        Map.of()));
        var result = call(Map.of("expectedCopyId", id, "command", "false"));
        assertThat(result.isError()).isFalse();
        assertThat(body(result).path("copyId").stringValue()).isEqualTo(id);
        assertThat(body(result).path("exitCode").intValue()).isEqualTo(1);
        verify(executor)
                .execute(
                        eq(principal),
                        eq(workspace),
                        eq("transport"),
                        eq(id),
                        eq(Optional.empty()),
                        eq("false"),
                        eq(Duration.ofSeconds(30)),
                        any());
    }

    @Test
    void mismatchIsExplicitButAnUnknownWorkerOutcomeNeverClaimsThatTheCommandDidNotRun() {
        String old = UUID.randomUUID().toString(), current = UUID.randomUUID().toString();
        when(executor.execute(any(), any(), anyString(), eq(old), any(), anyString(), any(), any()))
                .thenThrow(new SessionReplacedException(
                        SessionReplacedException.Reason.DIFFERENT_COPY, Optional.of(current)))
                .thenThrow(new SessionReplacedException(SessionReplacedException.Reason.MISSING_COPY, Optional.empty()))
                .thenThrow(new SessionReplacedException(SessionReplacedException.Reason.CLOSED_COPY, Optional.empty()))
                .thenThrow(new IllegalStateException("private worker detail"));
        var arguments = Map.<String, Object>of("expectedCopyId", old, "command", "poketto save note.md");
        var mismatch = call(arguments);
        assertThat(mismatch.isError()).isTrue();
        assertThat(body(mismatch).path("code").stringValue()).isEqualTo("SESSION_REPLACED");
        assertThat(body(mismatch).path("executed").booleanValue()).isFalse();
        assertThat(body(mismatch).path("copyId").stringValue()).isEqualTo(current);
        var unavailable = body(call(arguments));
        assertThat(unavailable.has("copyId")).isFalse();
        assertThat(unavailable.path("code").stringValue()).isEqualTo("SESSION_REPLACED");
        assertThat(unavailable.path("newCopyAllowed").booleanValue()).isTrue();
        var closed = body(call(arguments));
        assertThat(closed.path("reason").stringValue()).isEqualTo("CLOSED_COPY");
        assertThat(closed.path("newCopyAllowed").booleanValue()).isFalse();
        var unknown = body(call(arguments));
        assertThat(unknown.path("code").stringValue()).isEqualTo("UNAVAILABLE");
        assertThat(unknown.has("executed")).isFalse();
        assertThat(unknown.toString()).doesNotContain("private worker detail");
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return tool.callHandler().apply(exchange, new McpSchema.CallToolRequest("repo_exec", arguments));
    }

    private JsonNode body(McpSchema.CallToolResult result) {
        return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }
}
