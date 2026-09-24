package io.github.core607.poketto.capture;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Creates one new note in a space's private inbox from a link, a quoted passage, a note and an
 * optional image, the way a phone share sheet or a bookmarklet sends them. The caller needs only the
 * {@code CAPTURE} capability; the note is private, and nothing existing is read back or changed.
 */
public interface CaptureInbox {
    Captured capture(AuthPrincipal actor, WorkspaceId workspace, Capture capture, Optional<InputStream> image);

    /** What the sender chose to keep; blank fields are dropped. */
    record Capture(String title, String url, String text, String note) {
        public Capture {
            title = bounded(title, 200, "title");
            url = bounded(url, 2048, "url");
            text = bounded(text, 20_000, "text");
            note = bounded(note, 5_000, "note");
            if (!url.isEmpty() && !webAddress(url)) {
                throw new IllegalArgumentException("url must be an absolute http or https address");
            }
        }

        public boolean empty() {
            return url.isEmpty() && text.isEmpty() && note.isEmpty();
        }

        private static String bounded(String value, int maximum, String field) {
            String text = value == null ? "" : value.strip();
            if (text.codePointCount(0, text.length()) > maximum || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(field + " exceeds its text bounds");
            }
            return text;
        }

        private static boolean webAddress(String value) {
            try {
                URI address = new URI(value);
                return address.isAbsolute()
                        && ("http".equalsIgnoreCase(address.getScheme())
                                || "https".equalsIgnoreCase(address.getScheme()))
                        && address.getHost() != null;
            } catch (URISyntaxException invalid) {
                return false;
            }
        }
    }

    /** The created note's repository path and the commit that added it. */
    record Captured(String path, String commit) {}
}
