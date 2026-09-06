package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.Token;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

final class RepositoryMarkdownParser {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6} +(.+?) *#* *$");

    static String decode(byte[] bytes) {
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (source.indexOf('\0') >= 0) throw new IllegalArgumentException("text must not contain NUL bytes");
            return source;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("file is not valid UTF-8", exception);
        }
    }

    Metadata parse(String path, String source) {
        String body = source.startsWith("\ufeff") ? source.substring(1) : source;
        JsonNode metadata = null;
        boolean hasMetadata = body.startsWith("---\n") || body.startsWith("---\r\n");
        if (hasMetadata) {
            int first = body.indexOf('\n') + 1;
            int cursor = first;
            int end = -1;
            int next = -1;
            while (cursor <= body.length()) {
                int lineEnd = body.indexOf('\n', cursor);
                if (lineEnd < 0) lineEnd = body.length();
                String line = body.substring(cursor, lineEnd).replace("\r", "");
                if (line.equals("---")) {
                    end = cursor;
                    next = Math.min(lineEnd + 1, body.length());
                    break;
                }
                cursor = lineEnd + 1;
            }
            if (end < 0) throw new IllegalArgumentException("frontmatter requires a closing delimiter");
            String yaml = body.substring(first, end);
            if (yaml.getBytes(StandardCharsets.UTF_8).length > ContentLimits.MAX_FRONTMATTER_BYTES)
                throw new IllegalArgumentException("frontmatter exceeds its byte limit");
            try {
                var scanner = new ScannerImpl(new StreamReader(yaml), new LoaderOptions());
                while (scanner.peekToken() != null) {
                    Token token = scanner.getToken();
                    if (token.getTokenId() == Token.ID.Alias
                            || token.getTokenId() == Token.ID.Anchor
                            || token.getTokenId() == Token.ID.Tag)
                        throw new IllegalArgumentException("frontmatter aliases, anchors, and tags are not supported");
                    if (token.getTokenId() == Token.ID.StreamEnd) break;
                }
                metadata = YAML.readTree(yaml);
                if (metadata == null || !metadata.isObject())
                    throw new IllegalArgumentException("frontmatter must be a mapping");
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("frontmatter is not a valid bounded YAML mapping", exception);
            }
            body = body.substring(next);
        }
        String title = optionalText(metadata, "title")
                .orElse(firstHeading(body).orElse(path.substring(path.lastIndexOf('/') + 1, path.length() - 3)));
        title = title.strip();
        if (title.isEmpty() || title.codePointCount(0, title.length()) > ContentLimits.MAX_TITLE_LENGTH)
            throw new IllegalArgumentException("title must be nonempty and within its length limit");
        List<String> tags = new ArrayList<>();
        if (metadata != null && metadata.has("tags")) {
            JsonNode node = metadata.get("tags");
            if (!node.isArray() || node.size() > ContentLimits.MAX_TAGS)
                throw new IllegalArgumentException("tags must be a bounded sequence");
            for (JsonNode tag : node) {
                if (!tag.isString()) throw new IllegalArgumentException("tags must be strings");
                String value = tag.stringValue().strip();
                if (value.isEmpty() || value.codePointCount(0, value.length()) > ContentLimits.MAX_TAG_LENGTH)
                    throw new IllegalArgumentException("tag exceeds its length limit or is empty");
                if (!tags.contains(value)) tags.add(value);
            }
        }
        String route = RepositoryPathRules.validateRoute(
                optionalText(metadata, "route").orElseGet(() -> RepositoryPathRules.route(path)));
        if (RepositoryPathRules.folderPage(path) && !route.equals(RepositoryPathRules.route(path)))
            throw new IllegalArgumentException("index.md must use its folder route");
        Optional<Instant> createdAt = date(metadata, "created_at");
        if (createdAt.isEmpty()) createdAt = date(metadata, "date");
        return new Metadata(
                title, body, List.copyOf(tags), createdAt, date(metadata, "updated_at"), route, !hasMetadata);
    }

    private static Optional<String> firstHeading(String body) {
        char fence = 0;
        int fenceLength = 0;
        String previous = "";
        for (String line : body.split("\\r?\\n")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                char marker = stripped.charAt(0);
                int length = 0;
                while (length < stripped.length() && stripped.charAt(length) == marker) length++;
                if (fence == 0) {
                    fence = marker;
                    fenceLength = length;
                } else if (fence == marker
                        && length >= fenceLength
                        && stripped.substring(length).isBlank()) fence = 0;
                previous = "";
                continue;
            }
            if (fence != 0) continue;
            Matcher match = HEADING.matcher(line);
            if (match.matches()) return Optional.of(match.group(1));
            if (!previous.isBlank() && line.matches(" {0,3}(=+|-+) *")) return Optional.of(previous.strip());
            previous = line;
        }
        return Optional.empty();
    }

    private static Optional<String> optionalText(JsonNode metadata, String field) {
        if (metadata == null || !metadata.has(field)) return Optional.empty();
        JsonNode node = metadata.get(field);
        if (!node.isString()) throw new IllegalArgumentException(field + " must be a string");
        return Optional.of(node.stringValue());
    }

    private static Optional<Instant> date(JsonNode metadata, String field) {
        return optionalText(metadata, field).map(value -> {
            try {
                return value.length() == 10
                        ? LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
                        : OffsetDateTime.parse(value).toInstant();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(field + " must be an ISO date or offset timestamp", exception);
            }
        });
    }

    record Metadata(
            String title,
            String body,
            List<String> tags,
            Optional<Instant> createdAt,
            Optional<Instant> updatedAt,
            String route,
            boolean inferredMetadata) {}
}
