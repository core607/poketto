package io.github.core607.poketto.content.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

/**
 * Rewrites Markdown that leaves the repository as public text. Raw HTML and link reference
 * definitions are removed; every link and image destination is replaced by the caller's rewrite,
 * or flattened into its text when the rewrite refuses it. A document over 20,000 nodes or deeper
 * than 128 levels is refused with the caller's exception before anything is rewritten.
 */
final class PublicMarkdownSanitizer {
    private static final int MAX_NODES = 20_000;
    private static final int MAX_DEPTH = 128;
    private static final Parser PARSER = Parser.builder().build();
    private static final MarkdownRenderer RENDERER = MarkdownRenderer.builder().build();

    /** The public destination for an authored one, or null to flatten the link or image into its text. */
    @FunctionalInterface
    interface Destination {
        String rewrite(String authored, boolean image);
    }

    private PublicMarkdownSanitizer() {}

    static String sanitize(String body, Destination destination, Supplier<? extends RuntimeException> overBounds) {
        Node root = PARSER.parse(body);
        for (Node node : nodes(root, overBounds)) {
            if (node instanceof HtmlBlock || node instanceof HtmlInline || node instanceof LinkReferenceDefinition) {
                node.unlink();
            } else if (node instanceof Link link) {
                String target = destination.rewrite(link.getDestination(), false);
                if (target == null) {
                    flatten(node);
                } else {
                    link.setDestination(target);
                }
            } else if (node instanceof Image image) {
                String target = destination.rewrite(image.getDestination(), true);
                if (target == null) {
                    flatten(node);
                } else {
                    image.setDestination(target);
                }
            }
        }
        return RENDERER.render(root);
    }

    private static List<Node> nodes(Node root, Supplier<? extends RuntimeException> overBounds) {
        List<Node> nodes = new ArrayList<>();
        record Visit(Node node, int depth) {}
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(root, 0));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (nodes.size() >= MAX_NODES || visit.depth() > MAX_DEPTH) {
                throw overBounds.get();
            }
            nodes.add(visit.node());
            for (Node child = visit.node().getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(new Visit(child, visit.depth() + 1));
            }
        }
        return nodes;
    }

    private static void flatten(Node node) {
        while (node.getFirstChild() != null) {
            node.insertBefore(node.getFirstChild());
        }
        node.unlink();
    }
}
