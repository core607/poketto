package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryMediaIndex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lists logical index metadata, not proof that an original is currently available. */
final class MediaListing {
    /**
     * The page bound the worker reference states for {@code poketto media list}. A page is built
     * up to this size and then trimmed, so an entry that cannot fit alone is a listing this
     * protocol cannot express rather than a page to send half of.
     */
    static final int MAX_PAGE_BYTES = 12 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    record Query(String prefix, int offset, int limit, String version, String commit) {
        static Query parse(JsonNode arguments) {
            if (!arguments.isObject()
                    || !Set.copyOf(arguments.propertyNames())
                            .equals(Set.of("prefix", "offset", "limit", "indexVersion", "commit"))
                    || !arguments.path("prefix").isString()
                    || !arguments.path("offset").isIntegralNumber()
                    || !arguments.path("offset").canConvertToInt()
                    || !arguments.path("limit").isIntegralNumber()
                    || !arguments.path("limit").canConvertToInt()) {
                throw new IllegalArgumentException(
                        "a media listing takes a prefix, offset, limit, indexVersion and commit");
            }
            String prefix = arguments.path("prefix").stringValue();
            int offset = arguments.path("offset").intValue(),
                    limit = arguments.path("limit").intValue();
            if (prefix.getBytes(StandardCharsets.UTF_8).length > 4096
                    || prefix.indexOf('\0') >= 0
                    || offset < 0
                    || offset > RepositoryMediaIndex.MAX_FILES
                    || limit < 1
                    || limit > 200) {
                throw new IllegalArgumentException("a media listing offset and limit must be within their bounds");
            }
            return new Query(
                    prefix,
                    offset,
                    limit,
                    optionalHash(arguments.path("indexVersion"), 64),
                    optionalHash(arguments.path("commit"), 40));
        }

        private static String optionalHash(JsonNode value, int length) {
            if (value.isNull()) {
                return null;
            }
            if (!value.isString() || !value.stringValue().matches("[0-9a-f]{" + length + "}")) {
                throw new IllegalArgumentException(
                        "an optional hash must be null or lowercase hex of its exact length");
            }
            return value.stringValue();
        }
    }

    static BridgeReplies.Reply page(
            Map<String, RepositoryMediaIndex.Media> files, Query query, String version, String source) {
        if (query.version() != null && !query.version().equals(version)) {
            return BridgeReplies.staleIndex(version);
        }
        var matches = files.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(query.prefix()))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        var items = new ArrayList<BridgeReplies.MediaItem>();
        int next = Math.min(query.offset(), matches.size());
        while (next < matches.size() && items.size() < query.limit()) {
            var entry = matches.get(next);
            items.add(new BridgeReplies.MediaItem(
                    entry.getKey(),
                    entry.getValue().mediaType(),
                    entry.getValue().size()));
            // Measure the page as the caller will receive it, cursor included, before keeping it.
            var candidate = page(items, query, version, source, matches.size(), next + 1);
            if (JSON.writeValueAsBytes(candidate).length > MAX_PAGE_BYTES) {
                items.removeLast();
                if (items.isEmpty()) {
                    throw new IllegalArgumentException("Media entry exceeds page bound");
                }
                break;
            }
            next++;
        }
        return page(items, query, version, source, matches.size(), next);
    }

    private static BridgeReplies.Reply page(
            List<BridgeReplies.MediaItem> items, Query query, String version, String source, int total, int next) {
        return BridgeReplies.succeeded(new BridgeReplies.MediaPage(
                source,
                version,
                query.commit(),
                List.copyOf(items),
                total,
                query.offset(),
                next < total ? next : null));
    }

    private MediaListing() {}
}
