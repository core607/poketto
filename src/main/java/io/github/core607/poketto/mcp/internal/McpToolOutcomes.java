package io.github.core607.poketto.mcp.internal;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one record for every tool call, carrying the outcome code the caller received.
 *
 * <p>A refusal reaches the caller as a structured code and left no server-side trace, so the only
 * account of a failed call lived in the client's transcript. Recording the same code here lets a
 * reported code be looked up directly instead of reconstructed.
 *
 * <p>The code is read back from the result rather than passed in, because an operation can return
 * a refusal of its own without raising an exception; taking it from the body covers those too.
 * Arguments, commands and file paths are not recorded: a command line carries repository content
 * and a path names private material. For the same reason a failure the boundary could not map to a
 * specific code is recorded by its type chain alone, never by its message or stack.
 */
final class McpToolOutcomes {

    private static final Logger log = LoggerFactory.getLogger(McpToolOutcomes.class);

    private McpToolOutcomes() {}

    static McpSchema.CallToolResult recorded(ObjectMapper json, String tool, Supplier<McpSchema.CallToolResult> call) {
        long started = System.nanoTime();
        McpSchema.CallToolResult result = call.get();
        long milliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String outcome = result.isError() ? field(json, result, "code") : "OK";
        String reason = result.isError() ? field(json, result, "reason") : "NONE";
        var entry = result.isError() ? log.atWarn() : log.atInfo();
        // Values appear as key values for JSON records and in the message for the readable format,
        // because the console pattern renders the message alone.
        entry.addKeyValue("tool", tool)
                .addKeyValue("outcome", outcome)
                .addKeyValue("reason", reason)
                .addKeyValue("durationMs", milliseconds)
                .setMessage("mcp tool {} returned {} reason={} after {} ms")
                .addArgument(tool)
                .addArgument(outcome)
                .addArgument(reason)
                .addArgument(milliseconds)
                .log();
        return result;
    }

    /** Records where an unmapped failure came from: the exception types, outermost first. */
    static void failed(String tool, Throwable failure) {
        var types = new StringJoiner(" caused by ");
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            types.add(cause.getClass().getName());
        }
        log.atWarn()
                .addKeyValue("tool", tool)
                .addKeyValue("failure", types.toString())
                .setMessage("mcp tool {} failed: {}")
                .addArgument(tool)
                .addArgument(types)
                .log();
    }

    static McpSchema.CallToolResult failure(ObjectMapper json, String code, String reason, String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(json.writeValueAsString(new Failure(code, reason, message)))
                .isError(true)
                .build();
    }

    private record Failure(String code, String reason, String message) {}

    /**
     * Reads the {@code code} member of a refusal this service itself wrote. An absent or unreadable
     * code means the refusal was built without one, which is a defect in the producer rather than
     * caller input, so it is named instead of hidden.
     */
    private static String field(ObjectMapper json, McpSchema.CallToolResult result, String name) {
        if (result.content().isEmpty()) {
            return "UNREPORTED";
        }
        if (!(result.content().getFirst() instanceof McpSchema.TextContent text)) {
            return "UNREPORTED";
        }
        try {
            var code = json.readTree(text.text()).path(name);
            return code.isString() ? code.stringValue() : "UNREPORTED";
        } catch (JacksonException unreadable) {
            return "UNREPORTED";
        }
    }
}
