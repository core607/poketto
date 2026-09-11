package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Replaces parsed destinations in original source, preserving prose, code, HTML and line endings. */
final class MarkdownLinkRewriter {
    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    static String rewrite(String body, UnaryOperator<String> destination) {
        if (body.length() > ContentLimits.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Markdown repair exceeds its text bound");
        }
        Node root = PARSER.parse(body);
        List<Edit> edits = new ArrayList<>();
        int count = 0;
        for (Node node = root; node != null; ) {
            if (++count > 20_000) {
                throw new IllegalArgumentException("Markdown repair exceeds its node bound");
            }
            String old = node instanceof Link link
                    ? link.getDestination()
                    : node instanceof Image image
                            ? image.getDestination()
                            : node instanceof LinkReferenceDefinition definition ? definition.getDestination() : null;
            if (old != null) {
                String replacement = destination.apply(old);
                if (!old.equals(replacement)) {
                    Edit edit = locate(body, node, old, replacement);
                    if (edit != null) {
                        edits.add(edit);
                    }
                    if (edits.size() > 256) {
                        throw new IllegalArgumentException("Markdown repair exceeds its reference bound");
                    }
                }
            }
            if (node.getFirstChild() != null) {
                node = node.getFirstChild();
            } else {
                while (node != null && node.getNext() == null) {
                    node = node.getParent();
                }
                if (node != null) {
                    node = node.getNext();
                }
            }
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder result = new StringBuilder(body);
        int previous = body.length();
        for (Edit edit : edits) {
            if (edit.end() > previous) {
                throw new IllegalArgumentException("Markdown repair has overlapping destinations");
            }
            result.replace(edit.start(), edit.end(), edit.replacement());
            previous = edit.start();
        }
        return result.toString();
    }

    private static Edit locate(String body, Node node, String expected, String replacement) {
        var spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("Markdown destination has no source position");
        }
        // Span gaps include container prefixes such as "> "; they are not part of the link grammar.
        int length = spans.stream().mapToInt(span -> span.getLength() + 1).sum();
        int[] positions = new int[length];
        StringBuilder visible = new StringBuilder(length);
        for (var span : spans) {
            if (!visible.isEmpty()) {
                positions[visible.length()] = span.getInputIndex() - 1;
                visible.append('\n');
            }
            for (int i = 0; i < span.getLength(); i++) {
                positions[visible.length()] = span.getInputIndex() + i;
                visible.append(body.charAt(span.getInputIndex() + i));
            }
        }
        String source = visible.toString();
        if (node instanceof LinkReferenceDefinition) {
            // Definitions need no space after the colon and can place the destination on the next line.
            for (int marker = source.indexOf("]:"); marker >= 0; marker = source.indexOf("]:", marker + 1)) {
                Edit edit = token(source, marker + 2, expected, replacement);
                if (edit != null) {
                    return original(edit, positions);
                }
            }
        } else {
            // Reference uses are repaired at their definition, not at each label occurrence.
            if (!source.endsWith(")")) {
                return null;
            }
            for (int marker = source.lastIndexOf("]("); marker >= 0; marker = source.lastIndexOf("](", marker - 1)) {
                String suffix = "[x]" + source.substring(marker + 1);
                if (!isLink(suffix, expected)) {
                    continue;
                }
                Edit edit = token(source, marker + 2, expected, replacement);
                if (edit != null) {
                    return original(edit, positions);
                }
            }
        }
        throw new IllegalArgumentException("Markdown destination cannot be repaired without changing other source");
    }

    private static Edit original(Edit edit, int[] positions) {
        if (edit.start() == edit.end()) {
            throw new IllegalArgumentException("empty Markdown destination cannot be relocated");
        }
        return new Edit(positions[edit.start()], positions[edit.end() - 1] + 1, edit.replacement());
    }

    private static Edit token(String source, int cursor, String expected, String replacement) {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        int start = cursor;
        if (cursor < source.length() && source.charAt(cursor) == '<') {
            cursor++;
            while (cursor < source.length()) {
                char c = source.charAt(cursor++);
                if (c == '\\' && cursor < source.length()) {
                    cursor++;
                } else if (c == '>') {
                    break;
                }
            }
        } else {
            int depth = 0;
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (Character.isWhitespace(c) || (c == ')' && depth == 0)) {
                    break;
                }
                cursor++;
                if (c == '\\' && cursor < source.length()) {
                    cursor++;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        if (!isLink("[x](" + source.substring(start, cursor) + ")", expected)) {
            return null;
        }
        return new Edit(start, cursor, replacement);
    }

    private static boolean isLink(String source, String expected) {
        Node paragraph = PARSER.parse(source).getFirstChild();
        if (paragraph == null || paragraph.getNext() != null) {
            return false;
        }
        Node child = paragraph.getFirstChild();
        if (!(child instanceof Link link)
                || child.getNext() != null
                || !link.getDestination().equals(expected)) {
            return false;
        }
        var spans = link.getSourceSpans();
        var last = spans.getLast();
        return last.getInputIndex() + last.getLength() == source.length();
    }

    private record Edit(int start, int end, String replacement) {}
}
