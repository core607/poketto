package io.github.core607.poketto.qa.internal;

import java.io.IOException;
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
}
