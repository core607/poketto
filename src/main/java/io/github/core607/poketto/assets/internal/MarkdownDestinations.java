package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.content.ContentLimits;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

/** CommonMark AST destinations, not regular-expression matches inside code or raw HTML. */
public final class MarkdownDestinations {
    private static final Parser PARSER = Parser.builder().build();

    private MarkdownDestinations() {}

    public static Destinations parse(String body) {
        if (body == null || body.length() > ContentLimits.MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Markdown resolution exceeds its bound");
        Set<String> links = new LinkedHashSet<>();
        Set<String> images = new LinkedHashSet<>();
        Node root = PARSER.parse(body);
        Node node = root;
        int count = 0;
        while (node != null) {
            if (++count > 20_000) throw new IllegalArgumentException("Markdown node count exceeds its bound");
            if (node instanceof Image image) images.add(image.getDestination());
            if (node instanceof Link link) links.add(link.getDestination());
            if (links.size() + images.size() > 256)
                throw new IllegalArgumentException("Markdown reference count exceeds its bound");
            if (node.getFirstChild() != null) node = node.getFirstChild();
            else {
                while (node != null && node.getNext() == null) node = node.getParent();
                if (node != null) node = node.getNext();
            }
        }
        return new Destinations(links, images);
    }

    public static Optional<String> path(String document, String authored) {
        if (authored == null
                || authored.isEmpty()
                || authored.length() > 2048
                || authored.startsWith("//")
                || authored.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")
                || authored.contains("?")) return Optional.empty();
        String raw = authored.split("#", 2)[0];
        if (raw.isEmpty()) return Optional.empty();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < raw.length(); ) {
                char character = raw.charAt(i);
                if (character == '%') {
                    if (i + 2 >= raw.length()) return Optional.empty();
                    int high = Character.digit(raw.charAt(i + 1), 16);
                    int low = Character.digit(raw.charAt(i + 2), 16);
                    if (high < 0 || low < 0) return Optional.empty();
                    bytes.write((high << 4) | low);
                    i += 3;
                } else {
                    int codePoint = raw.codePointAt(i);
                    bytes.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
                    i += Character.charCount(codePoint);
                }
            }
            String decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
            if (decoded.startsWith("//")
                    || decoded.indexOf('\\') >= 0
                    || decoded.indexOf(':') >= 0
                    || decoded.codePoints().anyMatch(Character::isISOControl)) return Optional.empty();
            ArrayDeque<String> segments = new ArrayDeque<>();
            if (!decoded.startsWith("/") && document.contains("/")) {
                for (String segment :
                        document.substring(0, document.lastIndexOf('/')).split("/")) segments.addLast(segment);
            }
            for (String segment : decoded.split("/")) {
                if (segment.isEmpty() || segment.equals(".")) continue;
                if (segment.equals("..")) {
                    if (segments.isEmpty()) return Optional.empty();
                    segments.removeLast();
                } else {
                    if (segment.equalsIgnoreCase(".git") || segment.equalsIgnoreCase(".poketto"))
                        return Optional.empty();
                    segments.addLast(segment);
                }
            }
            String result = String.join("/", segments);
            return result.length() > ContentLimits.MAX_PATH_LENGTH ? Optional.empty() : Optional.of(result);
        } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public record Destinations(Set<String> links, Set<String> images) {
        public Destinations {
            links = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(links));
            images = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(images));
        }
    }
}
