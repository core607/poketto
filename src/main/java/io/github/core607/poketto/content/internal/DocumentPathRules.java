package io.github.core607.poketto.content.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

final class DocumentPathRules {

    private DocumentPathRules() {}

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
}
