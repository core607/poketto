package io.github.core607.poketto.content;

/**
 * The private inbox that capture keys may add to. A capture creates exactly one new Markdown file
 * directly inside it; anything else, including nested paths and edits, needs private writing.
 */
public final class CaptureInboxPaths {
    public static final String ROOT = "private/inbox/";

    private CaptureInboxPaths() {}

    /** True for a Markdown file directly inside the inbox. */
    public static boolean accepts(String path) {
        return path.startsWith(ROOT)
                && path.length() > ROOT.length() + ".md".length()
                && path.indexOf('/', ROOT.length()) < 0
                && path.endsWith(".md");
    }
}
