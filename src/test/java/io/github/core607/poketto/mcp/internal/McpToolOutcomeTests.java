package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A refusal reaches the caller as a code and previously left nothing behind, so these fix the
 * property that matters: the code the caller saw is the code a reader can look up.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpToolOutcomeTests {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void aRefusalIsRecordedWithTheCodeTheCallerReceived(CapturedOutput output) {
        McpSchema.CallToolResult result = McpToolOutcomes.recorded(
                json, "repo_exec", () -> refusal(Map.of("code", "SESSION_REPLACED", "reason", "MISSING_COPY")));

        assertThat(result.isError()).isTrue();
        assertThat(output).contains("mcp tool");
        assertThat(output).contains("SESSION_REPLACED", "reason=MISSING_COPY");
        assertThat(output).contains("repo_exec");
    }

    @Test
    void aSuccessfulCallIsRecordedWithoutItsOutput(CapturedOutput output) {
        McpSchema.CallToolResult body = McpSchema.CallToolResult.builder()
                .addTextContent("private/letters/from-kohaku.md")
                .isError(false)
                .build();

        McpToolOutcomes.recorded(json, "repo_exec", () -> body);

        assertThat(output).contains("mcp tool");
        assertThat(output).contains("OK");
        assertThat(output).doesNotContain("from-kohaku");
    }

    @Test
    void aRefusalWithoutACodeIsNamedRatherThanHidden(CapturedOutput output) {
        McpToolOutcomes.recorded(json, "get_artifact", () -> refusal(Map.of("message", "no code member")));

        assertThat(output).contains("UNREPORTED");
    }

    @Test
    void aRefusalCarryingUnreadableContentIsNamedRatherThanRaised(CapturedOutput output) {
        McpSchema.CallToolResult body = McpSchema.CallToolResult.builder()
                .addTextContent("not json at all")
                .isError(true)
                .build();

        McpToolOutcomes.recorded(json, "put_asset", () -> body);

        assertThat(output).contains("UNREPORTED");
    }

    @Test
    void theResultIsReturnedUnchanged() {
        McpSchema.CallToolResult body =
                McpSchema.CallToolResult.builder().addTextContent("done").build();

        assertThat(McpToolOutcomes.recorded(json, "repo_exec", () -> body)).isSameAs(body);
    }

    private McpSchema.CallToolResult refusal(Map<String, Object> body) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(body))
                .isError(true)
                .build();
    }
}
