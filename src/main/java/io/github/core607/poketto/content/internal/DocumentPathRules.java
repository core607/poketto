package io.github.core607.poketto.content.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

final class DocumentPathRules {

    private static final String MANAGED_PREFIX = "documents/";

    private DocumentPathRules() {
    }

    static String validate(String candidate) {
        Objects.requireNonNull(candidate, "document path must not be null");
        if (candidate.isEmpty()
                || candidate.startsWith("/")
                || candidate.indexOf('\\') >= 0
                || !candidate.startsWith(MANAGED_PREFIX)) {
            throw invalid(candidate);
        }

        String[] segments = candidate.split("/", -1);
        if (Arrays.stream(segments)
                .anyMatch(segment -> segment.isEmpty()
                        || segment.equals(".")
                        || segment.equals(".."))) {
            throw invalid(candidate);
        }
        if (!segments[segments.length - 1].endsWith(".md")) {
            throw new IllegalArgumentException(
                    "managed document path must end with lowercase .md: " + candidate);
        }
        return candidate;
    }

    static String collisionKey(String validatedPath) {
        return Arrays.stream(validatedPath.split("/", -1))
                .map(segment -> Normalizer.normalize(segment, Normalizer.Form.NFC))
                .map(DocumentPathRules::caseFold)
                .map(segment -> Normalizer.normalize(segment, Normalizer.Form.NFC))
                .collect(Collectors.joining("/"));
    }

    private static String caseFold(String value) {
        return value.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid(String candidate) {
        return new IllegalArgumentException(
                "managed document path must be a relative path below documents/: " + candidate);
    }
}
