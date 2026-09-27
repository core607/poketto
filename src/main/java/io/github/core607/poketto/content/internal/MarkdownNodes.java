package io.github.core607.poketto.content.internal;

import org.commonmark.node.Node;

/** Walking a parsed Markdown document. */
public final class MarkdownNodes {

    private MarkdownNodes() {}

    /**
     * The node after this one in document order, or null at the end of the document. Callers count
     * the nodes they visit themselves, because the bound each enforces belongs to what it is doing:
     * reading destinations out of a document and rewriting them are separate budgets.
     */
    public static Node next(Node node) {
        return next(node, null);
    }

    /** The node after this one in document order inside {@code root}, or null past the end of root. */
    public static Node next(Node node, Node root) {
        if (node.getFirstChild() != null) {
            return node.getFirstChild();
        }
        Node current = node;
        while (current != root && current.getNext() == null) {
            current = current.getParent();
        }
        return current == root ? null : current.getNext();
    }
}
