package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentMetadata;
import io.github.core607.poketto.content.DocumentVisibility;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.Scanner;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.TagToken;
import org.yaml.snakeyaml.tokens.TagTuple;
import org.yaml.snakeyaml.tokens.Token;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

final class CanonicalDocumentCodec {

    private static final byte[] UTF_8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final Set<String> FIELDS =
            Set.of("id", "title", "visibility", "tags", "created_at", "updated_at", "published_at");
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    DocumentContent parse(byte[] bytes) {
        Objects.requireNonNull(bytes, "document bytes must not be null");
        if (bytes.length > ContentLimits.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(
                    "document must not exceed " + ContentLimits.MAX_DOCUMENT_BYTES + " bytes: " + bytes.length);
        }
        if (startsWith(bytes, UTF_8_BOM)) {
            throw new IllegalArgumentException("document must not contain a UTF-8 byte-order mark");
        }

        String source = decodeUtf8(bytes);
        Sections sections = splitSections(source);
        int frontmatterBytes = sections.frontmatter().getBytes(StandardCharsets.UTF_8).length;
        if (frontmatterBytes > ContentLimits.MAX_FRONTMATTER_BYTES) {
            throw new IllegalArgumentException("document frontmatter must not exceed "
                    + ContentLimits.MAX_FRONTMATTER_BYTES + " bytes: " + frontmatterBytes);
        }
        rejectForbiddenYamlSyntax(sections.frontmatter());

        final JsonNode root;
        try {
            root = YAML.readTree(sections.frontmatter());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "document frontmatter is invalid YAML: " + exception.getOriginalMessage(), exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("document frontmatter must be a YAML mapping");
        }

        Set<String> unknownFields = new LinkedHashSet<>();
        root.propertyNames().forEach(field -> {
            if (!FIELDS.contains(field)) {
                unknownFields.add(field);
            }
        });
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException("document frontmatter contains unknown fields: " + unknownFields);
        }

        DocumentId id = DocumentId.parse(requiredText(root, "id"));
        String title = requiredText(root, "title");
        DocumentVisibility visibility = DocumentVisibility.parse(requiredText(root, "visibility"));
        List<String> tags = requiredTags(root);
        Instant createdAt = requiredUtcInstant(root, "created_at");
        Instant updatedAt = requiredUtcInstant(root, "updated_at");
        Optional<Instant> publishedAt = optionalUtcInstant(root, "published_at");

        return new DocumentContent(
                new DocumentMetadata(id, title, visibility, tags, createdAt, updatedAt, publishedAt), sections.body());
    }

    byte[] serialize(DocumentContent document) {
        Objects.requireNonNull(document, "document must not be null");
        DocumentMetadata metadata = document.metadata();
        StringBuilder canonical = new StringBuilder();
        canonical.append("---\n");
        canonical.append("id: ").append(metadata.id()).append('\n');
        canonical.append("title: ").append(quoted(metadata.title())).append('\n');
        canonical.append("visibility: ").append(metadata.visibility()).append('\n');
        if (metadata.tags().isEmpty()) {
            canonical.append("tags: []\n");
        } else {
            canonical.append("tags:\n");
            metadata.tags()
                    .forEach(tag -> canonical.append("  - ").append(quoted(tag)).append('\n'));
        }
        canonical.append("created_at: ").append(metadata.createdAt()).append('\n');
        canonical.append("updated_at: ").append(metadata.updatedAt()).append('\n');
        metadata.publishedAt()
                .ifPresent(publishedAt ->
                        canonical.append("published_at: ").append(publishedAt).append('\n'));
        canonical.append("---\n\n");

        String body = normalizeLineEndings(document.body());
        int bodyEnd = body.length();
        while (bodyEnd > 0 && body.charAt(bodyEnd - 1) == '\n') {
            bodyEnd--;
        }
        if (bodyEnd > 0) {
            canonical.append(body, 0, bodyEnd).append('\n');
        }
        byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ContentLimits.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(
                    "document must not exceed " + ContentLimits.MAX_DOCUMENT_BYTES + " bytes: " + bytes.length);
        }
        return bytes;
    }

    DocumentContent update(DocumentContent current, DocumentContent candidate, Instant changedAt) {
        Objects.requireNonNull(current, "current document must not be null");
        Objects.requireNonNull(candidate, "candidate document must not be null");
        Objects.requireNonNull(changedAt, "document change time must not be null");

        DocumentMetadata before = current.metadata();
        DocumentMetadata requested = candidate.metadata();
        if (!before.id().equals(requested.id())) {
            throw new IllegalArgumentException("document id is immutable after creation");
        }
        if (!before.createdAt().equals(requested.createdAt())) {
            throw new IllegalArgumentException("document created_at is immutable after creation");
        }
        if (before.publishedAt().isPresent() && !before.publishedAt().equals(requested.publishedAt())) {
            throw new IllegalArgumentException("document published_at cannot be changed or erased");
        }

        Instant comparableUpdatedAt = requested
                .publishedAt()
                .filter(publishedAt -> publishedAt.isAfter(before.updatedAt()))
                .orElse(before.updatedAt());
        DocumentContent comparable = new DocumentContent(
                new DocumentMetadata(
                        before.id(),
                        requested.title(),
                        requested.visibility(),
                        requested.tags(),
                        before.createdAt(),
                        comparableUpdatedAt,
                        requested.publishedAt()),
                candidate.body());
        if (Arrays.equals(serialize(current), serialize(comparable))) {
            return current;
        }
        if (!changedAt.isAfter(before.updatedAt())) {
            throw new IllegalArgumentException("document updated_at must advance when serialized content changes");
        }
        return new DocumentContent(
                new DocumentMetadata(
                        before.id(),
                        requested.title(),
                        requested.visibility(),
                        requested.tags(),
                        before.createdAt(),
                        changedAt,
                        requested.publishedAt()),
                candidate.body());
    }

    private static List<String> requiredTags(JsonNode root) {
        JsonNode tags = root.get("tags");
        if (tags == null || !tags.isArray()) {
            throw new IllegalArgumentException("document frontmatter field tags must be a YAML sequence");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (!tag.isString()) {
                throw new IllegalArgumentException("every document tag must be a string");
            }
            values.add(tag.stringValue());
        }
        return values;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("document frontmatter field " + field + " must be a string");
        }
        return value.stringValue();
    }

    private static Instant requiredUtcInstant(JsonNode root, String field) {
        return parseUtcInstant(requiredText(root, field), field);
    }

    private static Optional<Instant> optionalUtcInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("document frontmatter field " + field + " must be a string");
        }
        return Optional.of(parseUtcInstant(value.stringValue(), field));
    }

    private static Instant parseUtcInstant(String value, String field) {
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (parsed.getOffset().getTotalSeconds() != 0) {
                throw new IllegalArgumentException(
                        "document frontmatter field " + field + " must use a UTC offset: " + value);
            }
            return parsed.toInstant();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "document frontmatter field " + field + " must be an RFC 3339 UTC instant: " + value, exception);
        }
    }

    private static void rejectForbiddenYamlSyntax(String frontmatter) {
        try {
            Scanner scanner = new ScannerImpl(new StreamReader(frontmatter), new LoaderOptions());
            while (true) {
                Token token = scanner.peekToken();
                Token.ID id = token.getTokenId();
                if (id == Token.ID.Alias || id == Token.ID.Anchor) {
                    throw new IllegalArgumentException("document frontmatter must not contain YAML aliases or anchors");
                }
                if (id == Token.ID.Directive || id == Token.ID.DocumentStart || id == Token.ID.DocumentEnd) {
                    throw new IllegalArgumentException("document frontmatter must contain exactly one YAML document");
                }
                if (token instanceof TagToken tag && isCustomTag(tag.getValue())) {
                    throw new IllegalArgumentException("document frontmatter must not contain custom YAML tags");
                }
                if (id == Token.ID.StreamEnd) {
                    return;
                }
                scanner.getToken();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("document frontmatter is invalid YAML", exception);
        }
    }

    private static boolean isCustomTag(TagTuple tag) {
        return !("!!".equals(tag.getHandle())
                || (tag.getHandle() == null && tag.getSuffix().startsWith("tag:yaml.org,2002:")));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("document must be valid UTF-8", exception);
        }
    }

    private static Sections splitSections(String source) {
        Line opening = lineAt(source, 0);
        if (opening == null
                || !opening.content().equals("---")
                || opening.ending().isEmpty()) {
            throw new IllegalArgumentException("document must begin with a YAML frontmatter delimiter");
        }

        int cursor = opening.nextOffset();
        Line closing = null;
        while (cursor < source.length()) {
            Line line = lineAt(source, cursor);
            if (line == null) {
                break;
            }
            if (line.content().equals("---")) {
                closing = line;
                break;
            }
            cursor = line.nextOffset();
        }
        if (closing == null || closing.ending().isEmpty()) {
            throw new IllegalArgumentException("document frontmatter must end with a delimiter line");
        }

        String frontmatter = source.substring(opening.nextOffset(), closing.startOffset());
        int bodyStart = closing.nextOffset();
        if (source.startsWith("\r\n", bodyStart)) {
            bodyStart += 2;
        } else if (bodyStart < source.length()
                && (source.charAt(bodyStart) == '\n' || source.charAt(bodyStart) == '\r')) {
            bodyStart++;
        }
        String body = source.substring(bodyStart);
        if (body.endsWith("\r\n")) {
            body = body.substring(0, body.length() - 2);
        } else if (body.endsWith("\n") || body.endsWith("\r")) {
            body = body.substring(0, body.length() - 1);
        }
        return new Sections(frontmatter, body);
    }

    private static Line lineAt(String source, int start) {
        if (start >= source.length()) {
            return null;
        }
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\n') {
                int contentEnd = index > start && source.charAt(index - 1) == '\r' ? index - 1 : index;
                String ending = contentEnd == index ? "\n" : "\r\n";
                return new Line(start, source.substring(start, contentEnd), ending, index + 1);
            }
            if (current == '\r') {
                return new Line(start, source.substring(start, index), "\r", index + 1);
            }
        }
        return new Line(start, source.substring(start), "", source.length());
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String quoted(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    // YAML 1.1 readers treat U+2028 and U+2029 as line breaks and fold them
                    // inside quoted scalars, so they must never appear raw.
                    if (Character.isISOControl(codePoint) || codePoint == '\u2028' || codePoint == '\u2029') {
                        quoted.append(String.format("\\u%04x", codePoint));
                    } else {
                        quoted.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return quoted.append('"').toString();
    }

    private record Sections(String frontmatter, String body) {}

    private record Line(int startOffset, String content, String ending, int nextOffset) {}
}
