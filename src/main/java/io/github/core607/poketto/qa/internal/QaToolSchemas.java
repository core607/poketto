package io.github.core607.poketto.qa.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class QaToolSchemas {
    private QaToolSchemas() {}

    static JsonNode load(ObjectMapper json) {
        try (var stream = QaToolSchemas.class.getResourceAsStream("/qa/tools.json")) {
            if (stream == null) {
                throw new IllegalStateException("QA tool schema is missing");
            }
            return json.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("QA tool schema could not be loaded", failure);
        }
    }

    static List<ToolCallback> callbacks(ObjectMapper json) {
        var result = new ArrayList<ToolCallback>();
        for (JsonNode raw : load(json)) {
            Schema schema = json.treeToValue(raw, Schema.class);
            Function function = schema.function();
            result.add(new DefinitionOnly(ToolDefinition.builder()
                    .name(function.name())
                    .description(function.description())
                    .inputSchema(json.writeValueAsString(function.parameters()))
                    .build()));
        }
        return List.copyOf(result);
    }

    private record Schema(String type, Function function) {}

    private record Function(String name, String description, JsonNode parameters) {}

    private record DefinitionOnly(ToolDefinition getToolDefinition) implements ToolCallback {
        @Override
        public String call(String input) {
            throw new IllegalStateException("QA tools must run through the authorized application loop");
        }
    }
}
