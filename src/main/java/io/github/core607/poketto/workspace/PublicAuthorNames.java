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
        if (name.codePoints().anyMatch(PublicAuthorNames::invalidCharacter)) {
            throw new IllegalArgumentException("Public author name must be single-line Unicode text");
        }
        return name;
    }

    private static boolean invalidCharacter(int point) {
        return Character.isISOControl(point)
                || Character.getType(point) == Character.LINE_SEPARATOR
                || Character.getType(point) == Character.PARAGRAPH_SEPARATOR
                || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE);
    }

    public static String select(String article, String workspace) {
        return article.isEmpty() ? workspace : article;
    }
}
