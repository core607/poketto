package io.github.core607.poketto.content;

import static io.github.core607.poketto.content.internal.MarkdownNodes.next;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.DefinitionMap;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/** Plain reading text; link destinations, raw HTML and Markdown delimiters are not searchable prose. */
public final class MarkdownText {
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                    FootnotesExtension.create()))
            .build();

    private MarkdownText() {}

    public static String visible(String markdown) {
        return text(parse(markdown));
    }

    /** Omit only the opening level-one heading when its rendered text repeats the page title. */
    public static String summary(String title, String markdown) {
        Node root = parse(markdown);
        Node first = root.getFirstChild();
        if (first instanceof Heading heading && heading.getLevel() == 1) {
            if (text(heading).equals(title.strip())) {
                heading.unlink();
            }
        }
        return text(root);
    }

    private static Node parse(String markdown) {
        if (markdown == null || markdown.length() > ContentLimits.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Markdown reading text exceeds its bound");
        }
        Node root = PARSER.parse(markdown);
        resolveFootnotes(root);
        return root;
    }

    private static void resolveFootnotes(Node root) {
        var definitions = new DefinitionMap<>(FootnoteDefinition.class);
        var detached = new ArrayList<FootnoteDefinition>();
        for (Node node = root; node != null; node = next(node, root)) {
            if (node instanceof FootnoteDefinition definition) {
                definitions.putIfAbsent(definition.getLabel(), definition);
                detached.add(definition);
            }
        }
        detached.forEach(Node::unlink);
        var ordered = new ArrayList<FootnoteDefinition>();
        Set<FootnoteDefinition> visited = new LinkedHashSet<>();
        collectFootnotes(root, definitions, visited, ordered);
        for (int index = 0; index < ordered.size(); index++) {
            collectFootnotes(ordered.get(index), definitions, visited, ordered);
        }
        ordered.forEach(root::appendChild);
    }

    private static void collectFootnotes(
            Node root,
            DefinitionMap<FootnoteDefinition> definitions,
            Set<FootnoteDefinition> visited,
            List<FootnoteDefinition> ordered) {
        Node node = root;
        while (node != null) {
            Node following = next(node, root);
            if (node instanceof FootnoteReference reference) {
                FootnoteDefinition definition = definitions.get(reference.getLabel());
                if (definition == null) {
                    node.insertBefore(new Text("[^" + reference.getLabel() + "]"));
                    node.unlink();
                } else if (visited.add(definition)) {
                    ordered.add(definition);
                }
            }
            node = following;
        }
    }

    private static String text(Node root) {
        var result = new StringBuilder();
        Node node = root;
        while (node != null) {
            if (node instanceof Block || node instanceof TableCell) {
                result.append(' ');
            }
            switch (node) {
                case Text value -> result.append(value.getLiteral());
                case Code value -> result.append(value.getLiteral());
                case FencedCodeBlock value -> result.append(value.getLiteral());
                case IndentedCodeBlock value -> result.append(value.getLiteral());
                case SoftLineBreak ignored -> result.append(' ');
                case HardLineBreak ignored -> result.append(' ');
                default -> {}
            }
            node = next(node, root);
        }
        return result.toString().replaceAll("(?U)\\s+", " ").strip();
    }
}
