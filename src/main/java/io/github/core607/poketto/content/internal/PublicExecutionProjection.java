package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import tools.jackson.databind.json.JsonMapper;

/** Builds an independent reading namespace solely from approved publication fields. */
final class PublicExecutionProjection {
    private static final Parser PARSER = Parser.builder().build();
    private static final MarkdownRenderer RENDERER = MarkdownRenderer.builder().build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GUIDE = """
            # Public reading workspace

            This is a current public reading projection. It contains no original repository history,
            private content or source configuration. Article paths follow public routes.
            Route folders named index.md, AGENTS.md or .poketto, and names starting with ~, gain a ~ prefix.
            `.poketto/assets.json` lists authorized media originals that are not materialized here.
            An absent local media file is not a deletion. This projection cannot be saved to source.
            """;

    record Projection(
            Map<String, byte[]> files,
            Map<String, String> sourcePaths,
            Map<String, RepositorySnapshotExports.PublicMedia> media) {
        Projection {
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
            sourcePaths = Map.copyOf(sourcePaths);
            media = Map.copyOf(media);
        }
    }

    static String fingerprint(Projection projection) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(projection.files()).forEach((path, bytes) -> {
                digest.update((byte) 'F');
                hashField(digest, path.getBytes(StandardCharsets.UTF_8));
                hashField(digest, bytes);
            });
            new TreeMap<>(projection.sourcePaths()).forEach((path, source) -> {
                digest.update((byte) 'S');
                hashField(digest, path.getBytes(StandardCharsets.UTF_8));
                hashField(digest, source.getBytes(StandardCharsets.UTF_8));
            });
            new TreeMap<>(projection.media()).forEach((path, source) -> {
                digest.update((byte) 'M');
                hashField(digest, path.getBytes(StandardCharsets.UTF_8));
                hashField(digest, source.route().getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void hashField(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    static Projection build(
            PublicContentSnapshot snapshot,
            RepositoryMediaIndex originals,
            RepositoryPublishingPolicy policy,
            long maxBytes) {
        if (maxBytes < 1 || snapshot.articles().size() > ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) {
            throw invalid();
        }
        var articles = snapshot.articles().stream()
                .filter(article -> {
                    String source = article.repositoryPath().toLowerCase(Locale.ROOT);
                    return !source.equals("agents.md") && !source.endsWith("/agents.md");
                })
                .sorted(Comparator.comparing(PublicArticle::route))
                .toList();
        Set<String> normalized = new HashSet<>();
        Map<String, String> articlePaths = articlePaths(articles, normalized);
        requireNoNesting(normalized);
        String mediaRoot = mediaRoot(normalized);
        ProjectedMedia media = projectMedia(articles, originals, policy, mediaRoot);
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, String> sourcePaths = new HashMap<>();
        long total = 0;
        for (PublicArticle article : articles) {
            String path = articlePaths.get(article.repositoryPath());
            byte[] bytes = articleFile(article, path, articlePaths, media.paths());
            if (bytes.length > ContentLimits.MAX_DOCUMENT_BYTES || bytes.length > maxBytes - total) {
                throw invalid();
            }
            total += bytes.length;
            files.put(path, bytes);
            sourcePaths.put(path, article.repositoryPath());
        }
        byte[] index = new RepositoryMediaIndex(media.projected()).encode();
        byte[] guide = GUIDE.getBytes(StandardCharsets.UTF_8);
        if (index.length + guide.length > maxBytes - total) {
            throw invalid();
        }
        files.put(RepositoryMediaIndex.PATH, index);
        files.put("AGENTS.md", guide);
        media.paths().forEach((source, projected) -> sourcePaths.put(projected, source));
        return new Projection(files, sourcePaths, media.entries());
    }

    // Route folders that collide with index.md, AGENTS.md, .poketto or an existing ~ prefix gain a ~
    // prefix; two articles may not share a normalized path.
    private static Map<String, String> articlePaths(List<PublicArticle> articles, Set<String> normalized) {
        Map<String, String> articlePaths = new HashMap<>();
        for (PublicArticle article : articles) {
            RepositoryPathRules.validateRoute(article.route());
            String path = article.route().equals("/")
                    ? "index.md"
                    : Arrays.stream(article.route().substring(1).split("/"))
                                    .map(PublicExecutionProjection::projectedSegment)
                                    .collect(Collectors.joining("/"))
                            + "/index.md";
            RepositoryPathRules.validate(path);
            if (RepositoryPathRules.reserved(path)
                    || !normalized.add(DocumentPathRules.collisionKey(path))
                    || articlePaths.put(article.repositoryPath(), path) != null) {
                throw invalid();
            }
        }
        return articlePaths;
    }

    private static String projectedSegment(String segment) {
        String key = DocumentPathRules.collisionKey(segment);
        return segment.startsWith("~")
                        || Set.of("index.md", "agents.md", ".poketto").contains(key)
                ? "~" + segment
                : segment;
    }

    // No projected file may also be a directory of another projected file.
    private static void requireNoNesting(Set<String> normalized) {
        Set<String> filePaths = new HashSet<>(normalized);
        filePaths.add("agents.md");
        for (String key : filePaths) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                if (filePaths.contains(key.substring(0, slash))) {
                    throw invalid();
                }
            }
        }
    }

    private static String mediaRoot(Set<String> normalized) {
        int suffix = 0;
        String mediaPrefix = mediaRootPrefix(suffix);
        while (overlaps(normalized, mediaPrefix)) {
            mediaPrefix = mediaRootPrefix(++suffix);
        }
        return mediaPrefix.substring(0, mediaPrefix.length() - 1);
    }

    /** Referenced media: source path to projected path, the projected index, and each entry with its first referrer. */
    private record ProjectedMedia(
            Map<String, String> paths,
            Map<String, RepositoryMediaIndex.Media> projected,
            Map<String, RepositorySnapshotExports.PublicMedia> entries) {}

    private static ProjectedMedia projectMedia(
            List<PublicArticle> articles,
            RepositoryMediaIndex originals,
            RepositoryPublishingPolicy policy,
            String mediaRoot) {
        Map<String, RepositoryMediaIndex.Media> referenced = new TreeMap<>();
        Map<String, String> referrers = new HashMap<>();
        for (PublicArticle article : articles) {
            var references = MarkdownDestinations.parse(article.body());
            Stream.concat(references.links().stream(), references.images().stream())
                    .forEach(authored -> MarkdownDestinations.path(article.repositoryPath(), authored)
                            .ifPresent(path -> {
                                var media = originals.files().get(path);
                                if (media != null && policy.permitsPath(path)) {
                                    referenced.put(path, media);
                                    referrers.putIfAbsent(path, article.route());
                                }
                            }));
        }
        Map<String, String> mediaPaths = new HashMap<>();
        Map<String, RepositoryMediaIndex.Media> projectedMedia = new TreeMap<>();
        Map<String, RepositorySnapshotExports.PublicMedia> media = new TreeMap<>();
        int sequence = 0;
        for (var entry : referenced.entrySet()) {
            String source = entry.getKey();
            String path = mediaRoot + "/" + (++sequence) + "-" + source.substring(source.lastIndexOf('/') + 1);
            RepositoryPathRules.validate(path);
            mediaPaths.put(source, path);
            projectedMedia.put(path, entry.getValue());
            media.put(path, new RepositorySnapshotExports.PublicMedia(referrers.get(source), entry.getValue()));
        }
        return new ProjectedMedia(mediaPaths, projectedMedia, media);
    }

    private static byte[] articleFile(
            PublicArticle article, String path, Map<String, String> articlePaths, Map<String, String> mediaPaths) {
        String body = sanitize(article, path, articlePaths, mediaPaths);
        String text = "---\ntitle: " + JSON.writeValueAsString(article.title()) + "\ntags: "
                + JSON.writeValueAsString(article.tags()) + "\nroute: " + JSON.writeValueAsString(article.route())
                + "\npublic_author: " + JSON.writeValueAsString(article.publicAuthor())
                + (article.createdAt() == null
                        ? ""
                        : "\ndate: "
                                + JSON.writeValueAsString(article.createdAt().toString()))
                + (article.updatedAt() == null
                        ? ""
                        : "\nupdated_at: "
                                + JSON.writeValueAsString(article.updatedAt().toString()))
                + "\n---\n\n" + body;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String mediaRootPrefix(int suffix) {
        return (suffix == 0 ? "_media" : "_media_" + suffix) + "/";
    }

    private static boolean overlaps(Set<String> paths, String prefix) {
        return paths.stream().anyMatch(path -> path.startsWith(prefix));
    }

    private static String sanitize(
            PublicArticle article,
            String projectedPath,
            Map<String, String> articlePaths,
            Map<String, String> mediaPaths) {
        Node document = PARSER.parse(article.body());
        List<Node> nodes = new ArrayList<>();
        record Visit(Node node, int depth) {}
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(document, 0));
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (nodes.size() >= 20_000 || next.depth() > 128) {
                throw invalid();
            }
            nodes.add(next.node());
            for (Node child = next.node().getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(new Visit(child, next.depth() + 1));
            }
        }
        for (Node node : nodes) {
            if (node instanceof HtmlBlock || node instanceof HtmlInline || node instanceof LinkReferenceDefinition) {
                node.unlink();
            } else if (node instanceof Link link) {
                String destination = destination(
                        article.repositoryPath(),
                        projectedPath,
                        link.getDestination(),
                        articlePaths,
                        mediaPaths,
                        false);
                if (destination == null) {
                    flatten(node);
                } else {
                    link.setDestination(destination);
                }
            } else if (node instanceof Image image) {
                String destination = destination(
                        article.repositoryPath(),
                        projectedPath,
                        image.getDestination(),
                        articlePaths,
                        mediaPaths,
                        true);
                if (destination == null) {
                    flatten(node);
                } else {
                    image.setDestination(destination);
                }
            }
        }
        return RENDERER.render(document);
    }

    private static String destination(
            String source,
            String projected,
            String authored,
            Map<String, String> articles,
            Map<String, String> media,
            boolean image) {
        if (!image && literal(authored)) {
            return authored;
        }
        var resolved = MarkdownDestinations.path(source, authored);
        if (resolved.isEmpty()) {
            return null;
        }
        String target = target(resolved.orElseThrow(), articles, media, image);
        if (target == null) {
            return null;
        }
        return encode(RelativeLinks.relative(projected, target)) + fragment(authored);
    }

    // A link's own-page fragment or absolute web or mail destination is kept as authored.
    private static boolean literal(String authored) {
        return ((authored.startsWith("#") && authored.length() <= 256)
                        || authored.matches("(?i)^(https?://|mailto:).*"))
                && authored.codePoints().noneMatch(Character::isISOControl);
    }

    // Media resolves for links and images; an article resolves for links by path, folder landing or .md name.
    private static String target(
            String original, Map<String, String> articles, Map<String, String> media, boolean image) {
        String target = media.get(original);
        if (target != null || image) {
            return target;
        }
        for (String candidate : List.of(
                original,
                original.isEmpty() ? "index.md" : original + "/index.md",
                original.isEmpty() ? "README.md" : original + "/README.md",
                original + ".md")) {
            target = articles.get(candidate);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private static String encode(String relative) {
        return Arrays.stream(relative.split("/", -1))
                .map(segment -> segment.equals("..")
                        ? segment
                        : URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static String fragment(String authored) {
        int fragment = authored.indexOf('#');
        return fragment >= 0
                        && authored.length() - fragment <= 256
                        && authored.substring(fragment).codePoints().noneMatch(Character::isISOControl)
                ? authored.substring(fragment)
                : "";
    }

    private static void flatten(Node node) {
        while (node.getFirstChild() != null) {
            node.insertBefore(node.getFirstChild());
        }
        node.unlink();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("public execution projection exceeds its bounds or has conflicting paths");
    }
}
