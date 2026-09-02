package io.github.core607.poketto.content.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class DocumentPathRules {

    private static final String MANAGED_PREFIX = "documents/";
    // Windows cannot store these names, and the repository must check out on both platforms.
    private static final Pattern WINDOWS_RESERVED_NAME =
            Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");
    private static final Pattern WINDOWS_INVALID_CHARACTER = Pattern.compile("[<>:\"|?*\\x00-\\x1f\\x7f]");

    private DocumentPathRules() {}

    static String validate(String candidate) {
        Objects.requireNonNull(candidate, "document path must not be null");
        if (candidate.isEmpty()
                || candidate.startsWith("/")
                || candidate.indexOf('\\') >= 0
                || !candidate.startsWith(MANAGED_PREFIX)) {
            throw invalid(candidate);
        }

        String[] segments = candidate.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid(candidate);
            }
            if (WINDOWS_INVALID_CHARACTER.matcher(segment).find()
                    || WINDOWS_RESERVED_NAME.matcher(segment).matches()
                    || segment.endsWith(".")
                    || segment.endsWith(" ")) {
                throw new IllegalArgumentException(
                        "managed document path contains a name Windows cannot store: " + candidate);
            }
        }
        String fileName = segments[segments.length - 1];
        if (!fileName.endsWith(".md")) {
            throw new IllegalArgumentException("managed document path must end with lowercase .md: " + candidate);
        }
        if (fileName.length() == ".md".length()) {
            throw new IllegalArgumentException("managed document file name must not be empty before .md: " + candidate);
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
