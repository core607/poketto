package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.commonmark.node.*;
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
            Map<String, io.github.core607.poketto.content.RepositorySnapshotExports.PublicMedia> media) {
        Projection {
            files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
            sourcePaths = Map.copyOf(sourcePaths);
            media = Map.copyOf(media);
        }
    }

    static String fingerprint(Projection projection) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
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
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void hashField(java.security.MessageDigest digest, byte[] bytes) {
        digest.update(
                java.nio.ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    static Projection build(
            PublicContentSnapshot snapshot,
            RepositoryMediaIndex originals,
            RepositoryPublishingPolicy policy,
            long maxBytes) {
        if (maxBytes < 1 || snapshot.articles().size() > ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) throw invalid();
        Map<String, String> articlePaths = new HashMap<>();
        Set<String> normalized = new HashSet<>();
        var articles = snapshot.articles().stream()
                .filter(article -> {
                    String source = article.repositoryPath().toLowerCase(java.util.Locale.ROOT);
                    return !source.equals("agents.md") && !source.endsWith("/agents.md");
                })
                .sorted(java.util.Comparator.comparing(PublicArticle::route))
                .toList();
        for (PublicArticle article : articles) {
            RepositoryPathRules.validateRoute(article.route());
            String path = article.route().equals("/")
                    ? "index.md"
                    : java.util.Arrays.stream(article.route().substring(1).split("/"))
                                    .map(segment -> {
                                        String key = DocumentPathRules.collisionKey(segment);
                                        return segment.startsWith("~")
                                                        || Set.of("index.md", "agents.md", ".poketto")
                                                                .contains(key)
                                                ? "~" + segment
                                                : segment;
                                    })
                                    .collect(java.util.stream.Collectors.joining("/"))
                            + "/index.md";
            RepositoryPathRules.validate(path);
            if (RepositoryPathRules.reserved(path)
                    || !normalized.add(DocumentPathRules.collisionKey(path))
                    || articlePaths.put(article.repositoryPath(), path) != null) throw invalid();
        }
        Set<String> filePaths = new HashSet<>(normalized);
        filePaths.add("agents.md");
        for (String key : filePaths) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                if (filePaths.contains(key.substring(0, slash))) throw invalid();
            }
        }
        int suffix = 0;
        String mediaPrefix = mediaRootPrefix(suffix);
        while (overlaps(normalized, mediaPrefix)) mediaPrefix = mediaRootPrefix(++suffix);
        String mediaRoot = mediaPrefix.substring(0, mediaPrefix.length() - 1);
        Map<String, RepositoryMediaIndex.Media> referenced = new TreeMap<>();
        Map<String, String> referrers = new HashMap<>();
        for (PublicArticle article : articles) {
            var references = MarkdownDestinations.parse(article.body());
            java.util.stream.Stream.concat(references.links().stream(), references.images().stream())
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
        Map<String, io.github.core607.poketto.content.RepositorySnapshotExports.PublicMedia> media = new TreeMap<>();
        int sequence = 0;
        for (var entry : referenced.entrySet()) {
            String source = entry.getKey();
            String path = mediaRoot + "/" + (++sequence) + "-" + source.substring(source.lastIndexOf('/') + 1);
            RepositoryPathRules.validate(path);
            mediaPaths.put(source, path);
            projectedMedia.put(path, entry.getValue());
            media.put(
                    path,
                    new io.github.core607.poketto.content.RepositorySnapshotExports.PublicMedia(
                            referrers.get(source), entry.getValue()));
        }
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, String> sourcePaths = new HashMap<>();
        long total = 0;
        for (PublicArticle article : articles) {
            String path = articlePaths.get(article.repositoryPath());
            String body = sanitize(article, path, articlePaths, mediaPaths);
            String text = "---\ntitle: " + JSON.writeValueAsString(article.title()) + "\ntags: "
                    + JSON.writeValueAsString(article.tags()) + "\nroute: " + JSON.writeValueAsString(article.route())
                    + (article.createdAt() == null
                            ? ""
                            : "\ndate: "
                                    + JSON.writeValueAsString(
                                            article.createdAt().toString()))
                    + (article.updatedAt() == null
                            ? ""
                            : "\nupdated_at: "
                                    + JSON.writeValueAsString(
                                            article.updatedAt().toString()))
                    + "\n---\n\n" + body;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > ContentLimits.MAX_DOCUMENT_BYTES || bytes.length > maxBytes - total) throw invalid();
            total += bytes.length;
            files.put(path, bytes);
            sourcePaths.put(path, article.repositoryPath());
        }
        byte[] index = new RepositoryMediaIndex(projectedMedia).encode();
        byte[] guide = GUIDE.getBytes(StandardCharsets.UTF_8);
        if (index.length + guide.length > maxBytes - total) throw invalid();
        files.put(RepositoryMediaIndex.PATH, index);
        files.put("AGENTS.md", guide);
        mediaPaths.forEach((source, projected) -> sourcePaths.put(projected, source));
        return new Projection(files, sourcePaths, media);
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
            if (nodes.size() >= 20_000 || next.depth() > 128) throw invalid();
            nodes.add(next.node());
            for (Node child = next.node().getLastChild(); child != null; child = child.getPrevious())
                pending.push(new Visit(child, next.depth() + 1));
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
                if (destination == null) flatten(node);
                else link.setDestination(destination);
            } else if (node instanceof Image image) {
                String destination = destination(
                        article.repositoryPath(),
                        projectedPath,
                        image.getDestination(),
                        articlePaths,
                        mediaPaths,
                        true);
                if (destination == null) flatten(node);
                else image.setDestination(destination);
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
        if (!image
                && authored.startsWith("#")
                && authored.length() <= 256
                && authored.codePoints().noneMatch(Character::isISOControl)) return authored;
        if (!image
                && authored.matches("(?i)^(https?://|mailto:).*")
                && authored.codePoints().noneMatch(Character::isISOControl)) return authored;
        var resolved = MarkdownDestinations.path(source, authored);
        if (resolved.isEmpty()) return null;
        String original = resolved.orElseThrow();
        String target = media.get(original);
        if (target == null && !image) {
            target = articles.get(original);
            if (target == null) target = articles.get(original + "/index.md");
            if (target == null) target = articles.get(original + ".md");
        }
        if (target == null) return null;
        String[] parent = projected.contains("/")
                ? projected.substring(0, projected.lastIndexOf('/')).split("/")
                : new String[0];
        String[] destination = target.split("/");
        int common = 0;
        while (common < parent.length && common < destination.length && parent[common].equals(destination[common]))
            common++;
        List<String> parts = new ArrayList<>();
        for (int i = common; i < parent.length; i++) parts.add("..");
        for (int i = common; i < destination.length; i++) parts.add(destination[i]);
        String relative = String.join("/", parts);
        String encoded = java.util.Arrays.stream(relative.split("/", -1))
                .map(segment -> {
                    if (segment.equals("..")) return segment;
                    return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                            .replace("+", "%20");
                })
                .collect(java.util.stream.Collectors.joining("/"));
        int fragment = authored.indexOf('#');
        return fragment >= 0
                        && authored.length() - fragment <= 256
                        && authored.substring(fragment).codePoints().noneMatch(Character::isISOControl)
                ? encoded + authored.substring(fragment)
                : encoded;
    }

    private static void flatten(Node node) {
        while (node.getFirstChild() != null) node.insertBefore(node.getFirstChild());
        node.unlink();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("public execution projection exceeds its bounds or has conflicting paths");
    }
}
