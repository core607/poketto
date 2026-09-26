package io.github.core607.poketto.content.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

final class DocumentPathRules {

    private static final String MANAGED_ROOT = "documents";
    private static final String MANAGED_PREFIX = MANAGED_ROOT + "/";

    private DocumentPathRules() {}

    /**
     * Whether a tree entry falls inside the managed document area. The bare root name counts:
     * a repository may carry a regular file called {@code documents} where the directory
     * belongs, and that has to be refused rather than be walked past as something this module
     * does not manage.
     */
    static boolean isManaged(String path) {
        return path.equals(MANAGED_ROOT) || path.startsWith(MANAGED_PREFIX);
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
}
