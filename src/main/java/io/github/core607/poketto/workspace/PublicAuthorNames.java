package io.github.core607.poketto.workspace;

/** Explicit public signatures, independent of account and Git identities. */
public final class PublicAuthorNames {
    private PublicAuthorNames() {}

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Public author name is required; use an empty string for the fallback");
        }
        String name = value.strip();
        if (name.codePointCount(0, name.length()) > 120) {
            throw new IllegalArgumentException("Public author name must not exceed 120 Unicode code points");
        }
        if (name.codePoints()
                .anyMatch(point -> Character.isISOControl(point)
                        || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE))) {
            throw new IllegalArgumentException("Public author name must be single-line Unicode text");
        }
        return name;
    }

    public static String select(String article, String workspace) {
        return article.isEmpty() ? workspace : article;
    }
}
