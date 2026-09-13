package io.github.core607.poketto.content;

/** Valid Markdown exceeds the bounded traversal used to resolve links and media. */
public final class MarkdownResolutionLimitException extends IllegalArgumentException {
    public MarkdownResolutionLimitException(String message) {
        super(message);
    }
}
