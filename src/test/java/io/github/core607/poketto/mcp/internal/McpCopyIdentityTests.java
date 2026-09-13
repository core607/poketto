package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.mcp.SessionReplacedException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
        for (var arguments : List.of(
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
                        eq(new RepositoryExecutor.CopyRequest("new", null, false)),
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
                        Map.of(),
                        null));
        var initial = call(Map.of("expectedCopyId", "new", "command", "pwd"));
        assertThat(initial.isError()).isFalse();
        assertThat(body(initial).path("copyId").stringValue()).isEqualTo(id);
        when(executor.execute(
                        eq(principal),
                        eq(workspace),
                        eq("transport"),
                        eq(new RepositoryExecutor.CopyRequest(id, null, false)),
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
                        Map.of(),
                        null));
        var result = call(Map.of("expectedCopyId", id, "command", "false"));
        assertThat(result.isError()).isFalse();
        assertThat(body(result).path("copyId").stringValue()).isEqualTo(id);
        assertThat(body(result).path("exitCode").intValue()).isEqualTo(1);
        verify(executor)
                .execute(
                        eq(principal),
                        eq(workspace),
                        eq("transport"),
                        eq(new RepositoryExecutor.CopyRequest(id, null, false)),
                        eq(Optional.empty()),
                        eq("false"),
                        eq(Duration.ofSeconds(30)),
                        any());
    }

    @Test
    void mismatchIsExplicitButAnUnknownWorkerOutcomeNeverClaimsThatTheCommandDidNotRun() {
        String old = UUID.randomUUID().toString(), current = UUID.randomUUID().toString();
        when(executor.execute(
                        any(),
                        any(),
                        anyString(),
                        eq(new RepositoryExecutor.CopyRequest(old, null, false)),
                        any(),
                        anyString(),
                        any(),
                        any()))
                .thenThrow(new SessionReplacedException(
                        SessionReplacedException.Reason.DIFFERENT_COPY, Optional.of(current), false))
                .thenThrow(new SessionReplacedException(
                        SessionReplacedException.Reason.MISSING_COPY, Optional.empty(), true))
                .thenThrow(new SessionReplacedException(
                        SessionReplacedException.Reason.CLOSED_COPY, Optional.empty(), false))
                .thenThrow(new SessionReplacedException(
                        SessionReplacedException.Reason.CLOSED_COPY, Optional.empty(), true))
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
        var released = body(call(arguments));
        assertThat(released.path("newCopyAllowed").booleanValue()).isTrue();
        assertThat(released.path("executed").booleanValue()).isFalse();
        assertThat(released.has("copyId")).isFalse();
        var unknown = body(call(arguments));
        assertThat(unknown.path("code").stringValue()).isEqualTo("UNAVAILABLE");
        assertThat(unknown.has("executed")).isFalse();
        assertThat(unknown.toString()).doesNotContain("private worker detail");
    }

    @Test
    void explicitResumePassesOneRequestAndRefusalDoesNotClaimEarlierCommandsWereRolledBack() {
        String copy = UUID.randomUUID().toString();
        when(executor.execute(
                        any(),
                        any(),
                        anyString(),
                        eq(new RepositoryExecutor.CopyRequest(copy, 3L, true)),
                        any(),
                        anyString(),
                        any(),
                        any()))
                .thenThrow(new ExecutionAdmissionException(
                        ExecutionAdmissionException.Reason.GENERATION_MISMATCH, 4L, true));
        JsonNode refused = body(call(
                Map.of("expectedCopyId", copy, "expectedGeneration", 3, "resume", true, "command", "poketto status")));
        assertThat(refused.path("code").stringValue()).isEqualTo("EXECUTION_REFUSED");
        assertThat(refused.path("reason").stringValue()).isEqualTo("GENERATION_MISMATCH");
        assertThat(refused.path("executed").booleanValue()).isFalse();
        assertThat(refused.path("currentGeneration").longValue()).isEqualTo(4);
        assertThat(refused.path("recoveryAvailable").booleanValue()).isTrue();
        assertThat(refused.path("message").stringValue()).contains("may have partially completed");
    }

    @Test
    void invalidRecoveryFieldsNeverReachTheExecutor() {
        String copy = UUID.randomUUID().toString();
        for (var extra : List.of(
                Map.<String, Object>of("expectedCopyId", "new", "resume", true, "expectedGeneration", 1),
                Map.<String, Object>of("expectedCopyId", copy, "resume", true),
                Map.<String, Object>of("expectedCopyId", copy, "expectedGeneration", 0),
                Map.<String, Object>of("expectedCopyId", copy, "expectedGeneration", 1.5),
                Map.<String, Object>of(
                        "expectedCopyId", copy, "expectedGeneration", RepositoryExecutor.MAX_GENERATION + 1),
                Map.<String, Object>of("expectedCopyId", copy, "resume", "true"))) {
            var arguments = new HashMap<>(extra);
            arguments.put("command", "pwd");
            assertThat(body(call(arguments)).path("code").stringValue()).isEqualTo("INVALID_INPUT");
        }
        verifyNoInteractions(executor);
    }

    @Test
    void recoveredNonzeroResultExposesGenerationExpiryAndPriorInterruption() {
        String copy = UUID.randomUUID().toString();
        UUID interrupted = UUID.randomUUID();
        var expected = new RepositoryExecutor.CopyRequest(copy, 3L, true);
        when(executor.execute(any(), any(), anyString(), eq(expected), any(), anyString(), any(), any()))
                .thenReturn(new RepositoryExecutor.ExecutionResult(
                        copy,
                        "a".repeat(40),
                        7,
                        "restored",
                        "",
                        false,
                        false,
                        false,
                        RepositoryExecutor.TerminationReason.NORMAL,
                        Map.of(),
                        Map.of(),
                        new RepositoryExecutor.CopyRetention(4, 1800000000000L, true, interrupted)));
        McpSchema.CallToolResult result = call(
                Map.of("expectedCopyId", copy, "expectedGeneration", 3, "resume", true, "command", "poketto status"));
        assertThat(result.isError()).isFalse();
        JsonNode body = body(result);
        assertThat(body.path("copyId").stringValue()).isEqualTo(copy);
        assertThat(body.path("exitCode").intValue()).isEqualTo(7);
        assertThat(body.path("retention").path("generation").longValue()).isEqualTo(4);
        assertThat(body.path("retention").path("expiresAt").longValue()).isEqualTo(1800000000000L);
        assertThat(body.path("retention").path("resumed").booleanValue()).isTrue();
        assertThat(body.path("retention").path("lastInterruptedCommand").stringValue())
                .isEqualTo(interrupted.toString());
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return tool.callHandler().apply(exchange, new McpSchema.CallToolRequest("repo_exec", arguments));
    }

    private JsonNode body(McpSchema.CallToolResult result) {
        return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }
}
