package io.github.core607.poketto.content;

import io.github.core607.poketto.content.internal.RepositoryPathRules;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A versioned logical namespace. Index metadata never proves blob ownership or publication authority. */
public record RepositoryMediaIndex(Map<String, Media> files) {
    public static final String PATH = ".poketto/assets.json";
    public static final int MAX_BYTES = ContentLimits.MAX_DOCUMENT_BYTES;
    public static final int MAX_FILES = 10_000;
    private static final Set<String> ROOT_FIELDS = Set.of("version", "files");
    private static final Set<String> MEDIA_FIELDS = Set.of("assetId", "revision", "mediaType", "size");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RepositoryMediaIndex {
        if (files == null || files.size() > MAX_FILES) {
            throw invalid();
        }
        TreeMap<String, Media> ordered = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        for (var file : files.entrySet()) {
            String path = RepositoryPathRules.validate(file.getKey());
            String key = collisionKey(path);
            if (file.getValue() == null || !keys.add(key) || key.endsWith(".md")) {
                throw invalid();
            }
            for (String segment : key.split("/")) {
                if (segment.equals(".poketto")) {
                    throw invalid();
                }
            }
            ordered.put(path, file.getValue());
        }
        for (String key : keys) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                if (keys.contains(key.substring(0, slash))) {
                    throw invalid();
                }
            }
        }
        files = Collections.unmodifiableMap(ordered);
    }

    public record Media(UUID assetId, String revision, String mediaType, long size) {
        public Media {
            if (assetId == null
                    || revision == null
                    || !revision.matches("[0-9a-f]{64}")
                    || mediaType == null
                    || !mediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}")
                    || size < 1
                    || size > 128L * 1024 * 1024) {
                throw invalid();
            }
        }
    }

    public static RepositoryMediaIndex empty() {
        return new RepositoryMediaIndex(Map.of());
    }

    /** Strict UTF-8 JSON, with no unknown fields, duplicate keys, coercion or trailing document. */
    public static RepositoryMediaIndex parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid();
        }
        try {
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            JsonNode root = JSON.readTree(source);
            fields(root, ROOT_FIELDS);
            JsonNode version = root.get("version");
            JsonNode entries = root.get("files");
            if (!version.isIntegralNumber()
                    || version.intValue() != 1
                    || !version.asText().equals("1")
                    || !entries.isObject()
                    || entries.size() > MAX_FILES) {
                throw invalid();
            }
            Map<String, Media> files = new TreeMap<>();
            for (String path : entries.propertyNames()) {
                JsonNode entry = entries.get(path);
                fields(entry, MEDIA_FIELDS);
                String id = text(entry.get("assetId"));
                UUID uuid = UUID.fromString(id);
                if (!uuid.toString().equals(id)
                        || !entry.get("size").isIntegralNumber()
                        || !entry.get("size").canConvertToLong()) {
                    throw invalid();
                }
                files.put(
                        path,
                        new Media(
                                uuid,
                                text(entry.get("revision")),
                                text(entry.get("mediaType")),
                                entry.get("size").longValue()));
            }
            return new RepositoryMediaIndex(files);
        } catch (Exception exception) {
            // JSON parser errors and caller paths may contain private text.
            throw invalid();
        }
    }

    public byte[] encode() {
        var root = JSON.createObjectNode();
        root.put("version", 1);
        var entries = root.putObject("files");
        files.forEach((path, media) -> {
            var entry = entries.putObject(path);
            entry.put("assetId", media.assetId().toString());
            entry.put("revision", media.revision());
            entry.put("mediaType", media.mediaType());
            entry.put("size", media.size());
        });
        byte[] bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            bytes = JSON.writeValueAsBytes(root);
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid();
        }
        return bytes;
    }

    /** Rejects media overlays over Git files, symlinks, submodules or their parent/child paths. */
    public void requireNoGitCollisions(Iterable<String> gitPaths) {
        TreeSet<String> git = new TreeSet<>();
        for (String path : gitPaths) {
            git.add(collisionKey(path));
        }
        for (String path : files.keySet()) {
            String key = collisionKey(path);
            if (git.contains(key)) {
                throw invalid();
            }
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                if (git.contains(key.substring(0, slash))) {
                    throw invalid();
                }
            }
            String next = git.ceiling(key + "/");
            if (next != null && next.startsWith(key + "/")) {
                throw invalid();
            }
        }
    }

    private static void fields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(fields)) {
            throw invalid();
        }
    }

    private static String text(JsonNode node) {
        if (!node.isString()) {
            throw invalid();
        }
        return node.stringValue();
    }

    private static String collisionKey(String path) {
        String normalized = Normalizer.normalize(path, Normalizer.Form.NFC);
        return Normalizer.normalize(normalized.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid or oversized repository media index");
    }
}
