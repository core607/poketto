package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositoryEmptyException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** An empty repository is a named condition with its own code, not an unavailable authority. */
class McpEmptyRepositoryTests {
    private final McpSessions sessions = mock(McpSessions.class);
    private final ObjectMapper json = new ObjectMapper();
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

    @Test
    void anEmptyRepositoryIsReportedByItsOwnCodeNamingTheRepositoryConnection() {
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(McpCancellation.CONTEXT_KEY, new McpCancellation())));
        when(sessions.resolve(exchange)).thenThrow(new RepositoryEmptyException());
        var beans = new StaticListableBeanFactory(Map.of(
                "assets",
                mock(AssetService.class),
                "transfers",
                mock(ImageTransfers.class),
                "executor",
                mock(RepositoryExecutor.class)));
        var tool = new RepositoryMcpTools(
                        sessions,
                        mock(AuthService.class),
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        json,
                        beans.getBeanProvider(ImageTransfers.class))
                .specifications().stream()
                        .filter(value -> value.tool().name().equals("repo_exec"))
                        .findFirst()
                        .orElseThrow();

        var result = tool.callHandler()
                .apply(
                        exchange,
                        new McpSchema.CallToolRequest("repo_exec", Map.of("expectedCopyId", "new", "command", "ls")));

        JsonNode body = json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
        assertThat(result.isError()).isTrue();
        assertThat(body.path("code").asString()).isEqualTo("REPOSITORY_EMPTY");
        assertThat(body.path("message").asString()).contains("no commit", "repository connection");
    }
}
