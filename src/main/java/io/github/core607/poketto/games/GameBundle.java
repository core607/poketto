package io.github.core607.poketto.games;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Public package bytes only; no repository paths, history or account authority. */
public record GameBundle(
        int protocol,
        String source,
        @JsonInclude(JsonInclude.Include.ALWAYS) String presentation,
        Map<String, Resource> resources) {
    public static final int MAX_BYTES = 384 * 1024;

    public GameBundle {
        if (protocol != 1) {
            throw new IllegalArgumentException("Unsupported game protocol");
        }
        text(source, 256 * 1024, "Rule module");
        if (presentation != null) {
            text(presentation, 64 * 1024, "Presentation module");
        }
        if (resources == null || resources.size() > 32) {
            throw new IllegalArgumentException("A game may declare at most 32 resources");
        }
        resources = Collections.unmodifiableMap(new TreeMap<>(resources));
        if (resources.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Game resources cannot be null");
        }
        resources.keySet().forEach(name -> text(name, 256, "Resource name"));
    }

    public static String text(String value, int maximum, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.codePoints().anyMatch(code -> code >= 0xd800 && code <= 0xdfff)) {
            throw new IllegalArgumentException(name + " must be well formed Unicode");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + " exceeds its byte limit");
        }
        return value;
    }

    public record Resource(String mediaType, String data) {
        private static final Set<String> TYPES = Set.of(
                "image/png", "image/jpeg", "image/webp", "text/plain", "application/json", "application/octet-stream");

        public Resource {
            if (!TYPES.contains(mediaType)) {
                throw new IllegalArgumentException("Unsupported game resource type");
            }
            text(data, 256 * 1024, "Resource data");
            Base64.getDecoder().decode(data);
        }
    }
}
