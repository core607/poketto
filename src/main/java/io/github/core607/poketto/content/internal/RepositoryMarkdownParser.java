package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ArticleIdentityDrafts;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.workspace.PublicAuthorNames;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
            String source = StrictText.utf8(bytes);
            if (source.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("text must not contain NUL bytes");
            }
            return source;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("file is not valid UTF-8", exception);
        }
    }

    Metadata parse(String path, String source) {
        String body = source.startsWith("\ufeff") ? source.substring(1) : source;
        Frontmatter frontmatter = frontmatter(body);
        JsonNode metadata = frontmatter.metadata();
        body = frontmatter.body();
        String title = title(path, metadata, body);
        List<String> tags = tags(metadata);
        // Folder identity follows its path, including after a move that preserves authored frontmatter.
        String route = RepositoryPathRules.folderPage(path)
                ? RepositoryPathRules.route(path)
                : RepositoryPathRules.validateRoute(
                        optionalText(metadata, "route").orElseGet(() -> RepositoryPathRules.route(path)));
        Optional<Instant> createdAt = date(metadata, "created_at");
        if (createdAt.isEmpty()) {
            createdAt = date(metadata, "date");
        }
        return new Metadata(
                title,
                body,
                List.copyOf(tags),
                createdAt,
                date(metadata, "updated_at"),
                route,
                !frontmatter.present(),
                PublicAuthorNames.normalize(
                        optionalText(metadata, "public_author").orElse("")),
                articleId(metadata),
                metadata != null && metadata.has("id") && articleId(metadata) == null);
    }

    private static UUID articleId(JsonNode metadata) {
        if (metadata == null || !metadata.has("id") || !metadata.get("id").isString()) {
            return null;
        }
        try {
            return DocumentId.parse(metadata.get("id").stringValue()).value();
        } catch (IllegalArgumentException invalid) {
            // Optional identity must not turn previously readable Markdown into invalid content.
            return null;
        }
    }

    ArticleIdentityDrafts.Draft prepareIdentity(String source) {
        String bom = source.startsWith("\ufeff") ? "\ufeff" : "";
        String text = source.substring(bom.length());
        Frontmatter frontmatter = frontmatter(text);
        UUID existing = articleId(frontmatter.metadata());
        if (existing != null) {
            return new ArticleIdentityDrafts.Draft(source, existing);
        }
        if (frontmatter.metadata() != null && frontmatter.metadata().has("id")) {
            throw new IllegalArgumentException(
                    "existing article id must be a canonical lowercase UUID; correct it in source");
        }
        UUID id = UUID.randomUUID();
        String newline = text.indexOf('\n') > 0 && text.charAt(text.indexOf('\n') - 1) == '\r' ? "\r\n" : "\n";
        String field = "id: " + id + newline;
        String prepared;
        if (frontmatter.present()) {
            int insertion = text.indexOf('\n') + 1;
            prepared = bom + text.substring(0, insertion) + field + text.substring(insertion);
        } else {
            prepared = bom + "---" + newline + field + "---" + newline + text;
        }
        return new ArticleIdentityDrafts.Draft(prepared, id);
    }

    /** The frontmatter mapping (null when absent) and the body that follows it. */
    private record Frontmatter(JsonNode metadata, String body, boolean present) {}

    // Frontmatter opens on the first line and closes on a line holding only the delimiter.
    private static Frontmatter frontmatter(String body) {
        if (!body.startsWith("---\n") && !body.startsWith("---\r\n")) {
            return new Frontmatter(null, body, false);
        }
        int first = body.indexOf('\n') + 1;
        int cursor = first;
        int end = -1;
        int next = -1;
        while (cursor <= body.length()) {
            int lineEnd = body.indexOf('\n', cursor);
            if (lineEnd < 0) {
                lineEnd = body.length();
            }
            String line = body.substring(cursor, lineEnd).replace("\r", "");
            if (line.equals("---")) {
                end = cursor;
                next = Math.min(lineEnd + 1, body.length());
                break;
            }
            cursor = lineEnd + 1;
        }
        if (end < 0) {
            throw new IllegalArgumentException("frontmatter requires a closing delimiter");
        }
        String yaml = body.substring(first, end);
        if (yaml.getBytes(StandardCharsets.UTF_8).length > ContentLimits.MAX_FRONTMATTER_BYTES) {
            throw new IllegalArgumentException("frontmatter exceeds its byte limit");
        }
        return new Frontmatter(mapping(yaml), body.substring(next), true);
    }

    // Aliases, anchors and tags are refused by a token scan before the document is read.
    private static JsonNode mapping(String yaml) {
        try {
            var scanner = new ScannerImpl(new StreamReader(yaml), new LoaderOptions());
            while (scanner.peekToken() != null) {
                Token token = scanner.getToken();
                if (token.getTokenId() == Token.ID.Alias
                        || token.getTokenId() == Token.ID.Anchor
                        || token.getTokenId() == Token.ID.Tag) {
                    throw new IllegalArgumentException("frontmatter aliases, anchors, and tags are not supported");
                }
                if (token.getTokenId() == Token.ID.StreamEnd) {
                    break;
                }
            }
            JsonNode metadata = YAML.readTree(yaml);
            if (metadata == null || !metadata.isObject()) {
                throw new IllegalArgumentException("frontmatter must be a mapping");
            }
            return metadata;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("frontmatter is not a valid bounded YAML mapping", exception);
        }
    }

    private static String title(String path, JsonNode metadata, String body) {
        String title = optionalText(metadata, "title")
                .orElse(firstHeading(body).orElse(path.substring(path.lastIndexOf('/') + 1, path.length() - 3)))
                .strip();
        if (title.isEmpty() || title.codePointCount(0, title.length()) > ContentLimits.MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title must be nonempty and within its length limit");
        }
        return title;
    }

    private static List<String> tags(JsonNode metadata) {
        List<String> tags = new ArrayList<>();
        if (metadata == null || !metadata.has("tags")) {
            return tags;
        }
        JsonNode node = metadata.get("tags");
        if (!node.isArray() || node.size() > ContentLimits.MAX_TAGS) {
            throw new IllegalArgumentException("tags must be a bounded sequence");
        }
        for (JsonNode tag : node) {
            if (!tag.isString()) {
                throw new IllegalArgumentException("tags must be strings");
            }
            String value = tag.stringValue().strip();
            if (value.isEmpty() || value.codePointCount(0, value.length()) > ContentLimits.MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("tag exceeds its length limit or is empty");
            }
            if (!tags.contains(value)) {
                tags.add(value);
            }
        }
        return tags;
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
                while (length < stripped.length() && stripped.charAt(length) == marker) {
                    length++;
                }
                if (fence == 0) {
                    fence = marker;
                    fenceLength = length;
                } else if (fence == marker
                        && length >= fenceLength
                        && stripped.substring(length).isBlank()) {
                    fence = 0;
                }
                previous = "";
                continue;
            }
            if (fence != 0) {
                continue;
            }
            Matcher match = HEADING.matcher(line);
            if (match.matches()) {
                return Optional.of(match.group(1));
            }
            if (!previous.isBlank() && line.matches(" {0,3}(=+|-+) *")) {
                return Optional.of(previous.strip());
            }
            previous = line;
        }
        return Optional.empty();
    }

    private static Optional<String> optionalText(JsonNode metadata, String field) {
        if (metadata == null || !metadata.has(field)) {
            return Optional.empty();
        }
        JsonNode node = metadata.get(field);
        if (!node.isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
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
            boolean inferredMetadata,
            String publicAuthor,
            UUID articleId,
            boolean invalidArticleId) {}
}
