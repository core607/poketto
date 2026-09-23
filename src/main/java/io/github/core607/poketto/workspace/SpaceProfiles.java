package io.github.core607.poketto.workspace;

/** Owner-editable presentation of a space: its name and the short description on its website. */
public final class SpaceProfiles {
    /** Matches the name bound applied when a space is created. */
    public static final int NAME_LIMIT = 120;

    public static final int DESCRIPTION_LIMIT = 280;

    private SpaceProfiles() {}

    public static String name(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Space name is required");
        }
        String name = value.strip();
        if (name.isEmpty() || name.length() > NAME_LIMIT) {
            throw new IllegalArgumentException("Space name must contain 1-120 characters");
        }
        if (name.codePoints().anyMatch(point -> invalid(point, false))) {
            throw new IllegalArgumentException("Space name must be single-line text");
        }
        return name;
    }

    /** An empty description is allowed; line breaks are kept and normalized to LF. */
    public static String description(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Space description is required; use an empty string for none");
        }
        String description = value.replace("\r\n", "\n").strip();
        if (description.codePointCount(0, description.length()) > DESCRIPTION_LIMIT) {
            throw new IllegalArgumentException("Space description must not exceed 280 Unicode code points");
        }
        if (description.codePoints().anyMatch(point -> invalid(point, true))) {
            throw new IllegalArgumentException("Space description must be plain text");
        }
        return description;
    }

    private static boolean invalid(int point, boolean lineBreaks) {
        return (Character.isISOControl(point) && !(lineBreaks && point == '\n'))
                || Character.getType(point) == Character.LINE_SEPARATOR
                || Character.getType(point) == Character.PARAGRAPH_SEPARATOR
                || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE);
    }
}
