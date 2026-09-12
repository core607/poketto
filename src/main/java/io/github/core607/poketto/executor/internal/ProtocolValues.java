package io.github.core607.poketto.executor.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Value rules shared by the worker protocol frames. Each method returns its argument so that a
 * record's compact constructor reads as one line per field, and every message names the field it
 * rejected, because a protocol mismatch is diagnosed from the message alone: the frame itself must
 * not be logged.
 *
 * <p>Every rule here rejects with {@link IllegalArgumentException}. Callers decide what that means:
 * a request the caller built wrong is a programming error, while a malformed worker reply is
 * transport failure and is remapped to {@link WorkerUnavailableException} at the boundary that
 * reads it.
 */
final class ProtocolValues {

    private ProtocolValues() {}

    /** The canonical text form, so a reply cannot echo a different spelling of the same UUID. */
    static String uuid(String value, String field) {
        require(value != null, field, "must be present");
        try {
            require(UUID.fromString(value).toString().equals(value), field, "must be a canonical UUID");
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(field + " must be a canonical UUID", malformed);
        }
        return value;
    }

    /** Lowercase hexadecimal of an exact length, used for commits (40) and SHA-256 digests (64). */
    static String hex(String value, int length, String field) {
        require(value != null, field, "must be present");
        require(value.matches("[0-9a-f]{" + length + "}"), field, "must be " + length + " lowercase hex characters");
        return value;
    }

    static long inRange(long value, long minimum, long maximum, String field) {
        require(value >= minimum && value <= maximum, field, "must be between " + minimum + " and " + maximum);
        return value;
    }

    static int inRange(int value, int minimum, int maximum, String field) {
        require(value >= minimum && value <= maximum, field, "must be between " + minimum + " and " + maximum);
        return value;
    }

    /** Non-empty text bounded by its encoded size, because the wire limit counts bytes, not chars. */
    static String boundedText(String value, int maximumBytes, String field) {
        require(value != null, field, "must be present");
        require(!value.isEmpty(), field, "must not be empty");
        require(
                value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes,
                field,
                "must not exceed " + maximumBytes + " UTF-8 bytes");
        return value;
    }

    /** A command reaches a shell, so an embedded NUL would truncate it somewhere downstream. */
    static String withoutNul(String value, String field) {
        require(value.indexOf('\0') < 0, field, "must not contain a NUL character");
        return value;
    }

    /** A repository-relative selection list. The element bound is the wire limit, not a path rule. */
    static List<String> paths(List<String> values, int maximumCount, String field) {
        require(values != null, field, "must be present");
        require(values.size() <= maximumCount, field, "must not exceed " + maximumCount + " entries");
        for (String value : values) {
            boundedText(value, 4096, field + " entry");
        }
        return List.copyOf(values);
    }

    static void require(boolean satisfied, String field, String rule) {
        if (!satisfied) {
            throw new IllegalArgumentException(field + " " + rule);
        }
    }
}
