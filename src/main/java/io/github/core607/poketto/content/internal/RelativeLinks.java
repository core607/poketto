package io.github.core607.poketto.content.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * The link one document writes to reach another file beside it. Moves and portable exports both
 * rewrite Markdown destinations, and if they spelled the same relationship differently a reader
 * would land somewhere different depending on which of the two produced the document.
 */
final class RelativeLinks {

    private RelativeLinks() {}

    /**
     * The percent-encoded path from the directory holding {@code document} to {@code target}. Both
     * are repository-relative and already validated, so the walk is over path segments rather than
     * over the filesystem: nothing here consults local disk or its separator.
     */
    static String from(String document, String target) {
        String[] source = document.split("/");
        String[] destination = target.split("/");
        int common = 0;
        while (common < source.length - 1
                && common < destination.length
                && source[common].equals(destination[common])) {
            common++;
        }
        var segments = new ArrayList<String>();
        for (int i = common; i < source.length - 1; i++) {
            segments.add("..");
        }
        for (int i = common; i < destination.length; i++) {
            segments.add(destination[i]);
        }
        return encode(String.join("/", segments));
    }

    /**
     * Percent-encodes everything a Markdown destination cannot carry literally. The unreserved set
     * plus the separator is kept, so a path that needs no encoding comes back unchanged, and a
     * space or a bracket cannot end the destination early and leave the rest of it as visible text.
     */
    private static String encode(String path) {
        var encoded = new StringBuilder();
        for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
            int c = Byte.toUnsignedInt(value);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~/".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
            }
        }
        return encoded.toString();
    }
}
