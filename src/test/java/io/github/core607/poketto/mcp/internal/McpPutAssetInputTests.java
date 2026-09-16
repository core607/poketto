package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Jackson 3 reports a rejected record as a DatabindException, not the IllegalArgumentException the
 * tool boundary maps to INVALID_INPUT, so a client that passed a file path was told the service was
 * unavailable. These pin the code and the message a model needs to correct itself, and that the
 * record of an unmapped failure carries its types but neither its message nor its stack.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpPutAssetInputTests {
    private static final String KEY = "llm-homogenization-img-2026-09-16";
    private final AuthService auth = mock(AuthService.class);
    private final ImageTransfers transfers = mock(ImageTransfers.class);
    private final McpSessions sessions = mock(McpSessions.class);
    private final ObjectMapper json = new ObjectMapper();
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final McpServerFeatures.SyncToolSpecification tool;

    McpPutAssetInputTests() {
        when(sessions.resolve(exchange))
                .thenReturn(new McpSessions.Identity(mock(AuthPrincipal.class), WorkspaceId.random()));
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(McpCancellation.CONTEXT_KEY, new McpCancellation())));
        var beans = new StaticListableBeanFactory(Map.of(
                "assets",
                mock(AssetService.class),
                "transfers",
                transfers,
                "executor",
                mock(RepositoryExecutor.class)));
        tool = new RepositoryMcpTools(
                        sessions,
                        auth,
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        json,
                        beans.getBeanProvider(ImageTransfers.class))
                .specifications().stream()
                        .filter(value -> value.tool().name().equals("put_asset"))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void aFilePathInUploadModeIsInvalidInputThatNamesTheUploadFlow() {
        JsonNode body = body(call(Map.of("operationKey", KEY, "mode", "upload", "file", "/mnt/data/poster.png")));

        assertThat(body.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.path("message").asString())
                .contains("file reference", "mode=import", "cannot be read here")
                .doesNotContain("/mnt/data");
        verifyNoInteractions(auth, transfers);
    }

    @Test
    void aShapeMismatchNamesTheFieldItStoppedAtAndNotTheFile() {
        JsonNode url = body(call(Map.of("operationKey", KEY, "mode", "import", "url", Map.of("href", "https://x/y"))));
        JsonNode mode = body(call(Map.of("operationKey", KEY, "mode", List.of("import"))));

        assertThat(url.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(url.path("message").asString())
                .startsWith("url does not have the documented shape")
                .doesNotContain("file reference", "https://x/y");
        assertThat(mode.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(mode.path("message").asString()).startsWith("mode does not have the documented shape");
        JsonNode member = body(call(Map.of(
                "operationKey",
                KEY,
                "mode",
                "import",
                "file",
                Map.of("download_url", Map.of("href", "https://x/y"), "file_id", "f"))));
        assertThat(member.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(member.path("message").asString())
                .startsWith("file.download_url does not have the documented shape")
                .doesNotContain("file reference", "https://x/y");
        verifyNoInteractions(auth, transfers);
    }

    @Test
    void theRecordsOwnValidationMessageReachesTheCaller() {
        JsonNode missing = body(call(Map.of("operationKey", KEY, "mode", "import")));
        JsonNode both = body(call(Map.of(
                "operationKey", KEY, "mode", "upload", "file", Map.of("download_url", "https://x/y", "file_id", "f"))));

        assertThat(missing.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(missing.path("message").asString()).isEqualTo("import requires exactly one of url or file");
        assertThat(both.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(both.path("message").asString()).contains("upload mode takes no image source");
        verifyNoInteractions(auth, transfers);
    }

    @Test
    void anUnexpectedFailureIsRecordedByItsTypesAlone(CapturedOutput output) {
        when(sessions.resolve(exchange))
                .thenThrow(new IllegalStateException(
                        "registry offline at /srv/private/notes", new IOException("/srv/private/notes/socket")));

        JsonNode body = body(call(Map.of("operationKey", KEY, "mode", "upload")));

        assertThat(body.path("code").asString()).isEqualTo("UNAVAILABLE");
        assertThat(output)
                .contains("mcp tool put_asset failed: java.lang.IllegalStateException caused by java.io.IOException")
                .doesNotContain("registry offline", "/srv/private");
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return tool.callHandler().apply(exchange, new McpSchema.CallToolRequest("put_asset", arguments));
    }

    private JsonNode body(McpSchema.CallToolResult result) {
        return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }
}
