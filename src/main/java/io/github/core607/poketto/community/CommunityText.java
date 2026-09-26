package io.github.core607.poketto.community;

/** The Unicode rule every piece of reader-written community text meets. */
final class CommunityText {
    private CommunityText() {}

    /** Refuses a lone surrogate: text must encode as UTF-8 without replacement. */
    static void requireWellFormed(String value, String subject) {
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException(subject + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(subject + " contains invalid Unicode");
            }
        }
    }
}
