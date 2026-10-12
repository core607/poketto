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
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.DefinitionMap;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
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

    /**
     * The prose a reader meets first, for excerpts that answer no query. Headings, code, tables,
     * image alt text and the emphasized caption paragraph under a figure describe the page rather
     * than say what it is about. A document without such prose falls back to its summary.
     */
    public static String lead(String title, String markdown) {
        Node root = parse(markdown);
        boolean afterFigure = false;
        Node block = root.getFirstChild();
        while (block != null) {
            Node following = block.getNext();
            boolean figure = block instanceof Paragraph paragraph && figure(paragraph);
            if (figure
                    || block instanceof Heading
                    || block instanceof FencedCodeBlock
                    || block instanceof IndentedCodeBlock
                    || block instanceof TableBlock
                    || (afterFigure && caption(block))) {
                block.unlink();
            } else {
                unlinkImages(block);
            }
            afterFigure = figure;
            block = following;
        }
        String lead = text(root);
        return lead.isEmpty() ? summary(title, markdown) : lead;
    }

    // A paragraph of images, linked images and their inline captions, with no prose around them.
    private static boolean figure(Paragraph paragraph) {
        boolean image = false;
        for (Node child = paragraph.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Image || (child instanceof Link link && onlyImages(link))) {
                image = true;
            } else if (!(child instanceof Emphasis || blank(child))) {
                return false;
            }
        }
        return image;
    }

    private static boolean onlyImages(Node parent) {
        Node child = parent.getFirstChild();
        if (child == null) {
            return false;
        }
        for (; child != null; child = child.getNext()) {
            if (!(child instanceof Image || blank(child))) {
                return false;
            }
        }
        return true;
    }

    private static boolean caption(Node block) {
        if (!(block instanceof Paragraph)) {
            return false;
        }
        int emphasis = 0;
        for (Node child = block.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Emphasis) {
                emphasis++;
            } else if (!blank(child)) {
                return false;
            }
        }
        return emphasis == 1;
    }

    private static boolean blank(Node node) {
        return node instanceof SoftLineBreak
                || node instanceof HardLineBreak
                || (node instanceof Text text && text.getLiteral().isBlank());
    }

    private static void unlinkImages(Node root) {
        var images = new ArrayList<Image>();
        for (Node node = root; node != null; node = next(node, root)) {
            if (node instanceof Image image) {
                images.add(image);
            }
        }
        images.forEach(Node::unlink);
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
