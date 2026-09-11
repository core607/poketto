package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryDocument;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.content.RepositoryOriginalTransfers;
import io.github.core607.poketto.content.RepositoryTree;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
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

/** Selects one committed content package. Retained plans and their source coordinates are host-only. */
final class PortableContentPlanner {
    private static final long MAX_MEDIA_BYTES = 512L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AuthService auth;
    private final RepositoryContentReader reader;
    private final RepositoryBlobReader blobs;
    private final PublicContentSnapshots snapshots;
    private final RepositoryOriginalTransfers originals;
    private final Map<Revision, String> fingerprints = Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Revision, String> eldest) {
            return size() > 64;
        }
    });

    private record Revision(WorkspaceId workspace, String commit) {}

    record Plan(WorkspaceId workspace, List<PortableArchiveWriter.Entry> entries, Runnable authorize) {
        Plan {
            entries = List.copyOf(entries);
        }
    }

    PortableContentPlanner(
            AuthService auth,
            RepositoryContentReader reader,
            RepositoryBlobReader blobs,
            PublicContentSnapshots snapshots,
            RepositoryOriginalTransfers originals) {
        this.auth = auth;
        this.reader = reader;
        this.blobs = blobs;
        this.snapshots = snapshots;
        this.originals = originals;
    }

    Plan prepare(AuthPrincipal actor, WorkspaceId workspace, List<String> selections, boolean publicOnly) {
        selections = List.copyOf(selections);
        if (selections.isEmpty() || selections.size() > 128 || new HashSet<>(selections).size() != selections.size()) {
            throw unavailable();
        }
        for (String path : selections) {
            if (!path.isEmpty()) {
                RepositoryPathRules.validate(path);
                if (internal(path)) {
                    throw unavailable();
                }
            }
        }
        Runnable identity = () -> auth.authorize(
                actor, workspace, publicOnly ? new Capability[0] : new Capability[] {Capability.READ_PRIVATE});
        identity.run();
        RepositoryTree tree = reader.readTree(workspace, Optional.empty());
        if (!tree.workspaceId().equals(workspace)) {
            throw unavailable();
        }
        String commit = tree.commit().orElseThrow(PortableContentPlanner::unavailable);
        var media = blobs.media(workspace, commit);
        if (!media.workspaceId().equals(workspace) || !media.commit().equals(commit)) {
            throw unavailable();
        }
        PublicContentSnapshot published = publicOnly ? snapshots.withCurrent(workspace, value -> value) : null;
        if (publicOnly
                && (!published.workspaceId().equals(workspace)
                        || !published.commit().equals(Optional.of(commit)))) {
            throw unavailable();
        }
        Map<String, PublicArticle> publicArticles = new HashMap<>();
        if (publicOnly) {
            published.articles().forEach(article -> publicArticles.put(article.repositoryPath(), article));
        }
        Set<String> publicReferences = publicOnly ? referencedPaths(published.articles()) : Set.of();
        String admitted = publicOnly ? fingerprint(workspace, published) : null;
        Runnable check = () -> {
            identity.run();
            if (publicOnly) {
                snapshots.withCurrent(workspace, current -> {
                    if (!admitted.equals(fingerprint(workspace, current))) {
                        throw unavailable();
                    }
                    return null;
                });
            }
        };
        var selected = new TreeMap<String, RepositoryDocument>();
        var selectedMedia = new TreeSet<String>();
        for (String selection : selections) {
            int matches = 0;
            for (var diagnostic : tree.diagnostics()) {
                if (inside(diagnostic.path(), selection)
                        && !internal(diagnostic.path())
                        && !diagnostic.code().equals("INFERRED_METADATA")) {
                    throw unavailable();
                }
            }
            for (var document : tree.documents()) {
                String path = document.file().path();
                if (!inside(path, selection) || internal(path)) {
                    continue;
                }
                if (!document.file().workspaceId().equals(workspace)
                        || !document.file().commit().equals(Optional.of(commit))) {
                    throw unavailable();
                }
                if (publicOnly && !publicArticles.containsKey(path)) {
                    throw unavailable();
                }
                selected.put(path, document);
                matches++;
            }
            for (String path : media.index().files().keySet()) {
                if (inside(path, selection)) {
                    if (publicOnly && (!media.publicPaths().contains(path) || !publicReferences.contains(path))) {
                        throw unavailable();
                    }
                    selectedMedia.add(path);
                    matches++;
                }
            }
            if (matches == 0) {
                throw unavailable();
            }
        }
        var builder = new Builder(workspace, commit, publicOnly, media, publicArticles, publicReferences, selected);
        for (var document : tree.documents()) {
            if (!publicOnly || publicArticles.containsKey(document.file().path())) {
                builder.articleRoutes.add(document.route());
            }
        }
        for (String path : selectedMedia) {
            builder.indexed(path);
        }
        for (var entry : selected.entrySet()) {
            check.run();
            String path = entry.getKey();
            String archive = builder.documents.get(path);
            String text;
            if (publicOnly) {
                var article = publicArticles.get(path);
                text = "---\ntitle: " + JSON.writeValueAsString(article.title()) + "\ntags: "
                        + JSON.writeValueAsString(article.tags()) + "\n---\n\n"
                        + publicBody(article.body(), authored -> builder.destination(path, archive, authored));
            } else {
                String source = entry.getValue().file().source().orElseThrow(PortableContentPlanner::unavailable);
                String body = entry.getValue().body();
                if (!source.endsWith(body)) {
                    throw unavailable();
                }
                text = source.substring(0, source.length() - body.length())
                        + MarkdownLinkRewriter.rewrite(body, authored -> builder.destination(path, archive, authored));
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > ContentLimits.MAX_DOCUMENT_BYTES
                    || bytes.length > ContentLimits.MAX_WORKSPACE_BYTES - builder.textBytes) {
                throw unavailable();
            }
            builder.textBytes += bytes.length;
            builder.entries.put(
                    archive, new PortableArchiveWriter.Entry(archive, bytes.length, output -> output.write(bytes)));
            builder.bound();
        }
        check.run();
        return new Plan(workspace, List.copyOf(builder.entries.values()), check);
    }

    private final class Builder {
        final WorkspaceId workspace;
        final String commit;
        final boolean publicOnly;
        final RepositoryMediaSnapshot media;
        final Map<String, PublicArticle> published;
        final Set<String> publicReferences;
        final Map<String, String> documents = new HashMap<>();
        final Map<String, String> routes = new HashMap<>();
        final Set<String> articleRoutes = new HashSet<>();
        final Map<String, String> originalPaths = new HashMap<>();
        final Map<String, PortableArchiveWriter.Entry> entries = new TreeMap<>();
        long mediaBytes, textBytes;

        Builder(
                WorkspaceId workspace,
                String commit,
                boolean publicOnly,
                RepositoryMediaSnapshot media,
                Map<String, PublicArticle> published,
                Set<String> publicReferences,
                Map<String, RepositoryDocument> selected) {
            this.workspace = workspace;
            this.commit = commit;
            this.publicOnly = publicOnly;
            this.media = media;
            this.published = published;
            this.publicReferences = publicReferences;
            int ordinal = 0;
            for (var entry : selected.entrySet()) {
                String target = publicOnly ? "content/article-" + (++ordinal) + ".md" : "content/" + entry.getKey();
                documents.put(entry.getKey(), target);
                String route = entry.getValue().route();
                if (routes.containsKey(route)) {
                    routes.put(route, "");
                } else {
                    routes.put(route, target);
                }
            }
        }

        String indexed(String path) {
            if (publicOnly && (!media.publicPaths().contains(path) || !publicReferences.contains(path))) {
                throw unavailable();
            }
            var entry = media.index().files().get(path);
            if (entry == null) {
                throw unavailable();
            }
            return managed("indexed:" + path, entry.assetId(), entry.revision(), entry.size(), entry.mediaType());
        }

        String managed(String key, UUID identity, String revision, long expectedSize, String expectedType) {
            if (originalPaths.containsKey(key)) {
                return originalPaths.get(key);
            }
            var asset = originals.describe(workspace, identity, revision);
            if (expectedSize >= 0 && (expectedSize != asset.size() || !expectedType.equals(asset.mediaType()))) {
                throw unavailable();
            }
            String target = nextMedia(asset.size(), extension(asset.mediaType()));
            entries.put(target, new PortableArchiveWriter.Entry(target, asset.size(), output -> {
                // Legacy managed images are published by exact article references, not index paths.
                // They retain the same image-only admission policy as the public rendering service.
                if (publicOnly && key.startsWith("managed:")) {
                    originals.copyImageTo(workspace, identity, revision, output);
                } else {
                    originals.copyTo(workspace, identity, revision, output);
                }
            }));
            originalPaths.put(key, target);
            bound();
            return target;
        }

        String destination(String source, String archive, String authored) {
            String raw = authored.split("#", 2)[0];
            String fragment = authored.substring(raw.length());
            if (raw.startsWith("managed:")) {
                String[] fields = raw.split(":", -1);
                if (fields.length != 3) {
                    throw unavailable();
                }
                String target = managed(raw, UUID.fromString(fields[1]), fields[2], -1, "");
                return relative(archive, target) + fragment;
            }
            var resolved = MarkdownDestinations.path(source, authored);
            if (resolved.isEmpty()) {
                if (raw.isEmpty() || raw.startsWith("//") || raw.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                    return authored;
                }
                throw unavailable();
            }
            String path = resolved.orElseThrow();
            if (internal(path)) {
                throw unavailable();
            }
            String target = documents.get(path);
            if (target == null && raw.startsWith("/")) {
                target = routes.get(raw);
            }
            if (target != null && !target.isEmpty()) {
                return relative(archive, target) + fragment;
            }
            if (raw.startsWith("/") && articleRoutes.contains(raw)) {
                return authored;
            }
            if (media.index().files().containsKey(path)) {
                return relative(archive, indexed(path)) + fragment;
            }
            // Article links never recursively add documents. Public exports must not name private targets.
            if (RepositoryPathRules.markdown(path)) {
                if (publicOnly && !published.containsKey(path)) {
                    throw unavailable();
                }
                return authored;
            }
            String key = "git:" + path;
            if (!originalPaths.containsKey(key)) {
                var blob = blobs.find(workspace, commit, path).orElseThrow(PortableContentPlanner::unavailable);
                if (!blob.workspaceId().equals(workspace)
                        || !blob.commit().equals(commit)
                        || (publicOnly && !blob.publicPath())) {
                    throw unavailable();
                }
                String generated = nextMedia(blob.size(), suffix(path));
                entries.put(
                        generated,
                        new PortableArchiveWriter.Entry(
                                generated, blob.size(), output -> output.write(blobs.read(blob))));
                originalPaths.put(key, generated);
                bound();
            }
            return relative(archive, originalPaths.get(key)) + fragment;
        }

        String nextMedia(long size, String extension) {
            if (size > MAX_MEDIA_BYTES - mediaBytes) {
                throw unavailable();
            }
            mediaBytes += size;
            return "media/original-" + (originalPaths.size() + 1) + extension;
        }

        void bound() {
            if (entries.size() > 10_000) {
                throw unavailable();
            }
        }
    }

    private String fingerprint(WorkspaceId workspace, PublicContentSnapshot snapshot) {
        if (!snapshot.workspaceId().equals(workspace)) {
            throw unavailable();
        }
        String commit = snapshot.commit().orElseThrow(PortableContentPlanner::unavailable);
        var revision = new Revision(workspace, commit);
        return fingerprints.computeIfAbsent(revision, ignored -> {
            var media = blobs.media(workspace, commit);
            if (!media.workspaceId().equals(workspace) || !media.commit().equals(commit)) {
                throw unavailable();
            }
            var included = new TreeMap<String, RepositoryMediaIndex.Media>();
            var git = new TreeMap<String, Object>();
            var paths = referencedPaths(snapshot.articles());
            for (String path : paths) {
                if (media.index().files().containsKey(path)) {
                    if (media.publicPaths().contains(path)) {
                        included.put(path, media.index().files().get(path));
                    }
                } else if (!RepositoryPathRules.markdown(path)) {
                    blobs.find(workspace, commit, path)
                            .ifPresent(blob -> git.put(path, List.of(blob.objectId(), blob.publicPath())));
                }
            }
            return DocumentRevision.sha256(JSON.writeValueAsBytes(List.of(snapshot.articles(), included, git)))
                    .value();
        });
    }

    private static Set<String> referencedPaths(Collection<PublicArticle> articles) {
        var paths = new HashSet<String>();
        for (var article : articles) {
            var refs = MarkdownDestinations.parse(article.body());
            Stream.concat(refs.links().stream(), refs.images().stream())
                    .forEach(ref -> MarkdownDestinations.path(article.repositoryPath(), ref)
                            .filter(path -> !internal(path))
                            .ifPresent(paths::add));
            if (paths.size() > 100_000) {
                throw unavailable();
            }
        }
        return Set.copyOf(paths);
    }

    private static boolean inside(String path, String selection) {
        return selection.isEmpty() || path.equals(selection) || path.startsWith(selection + "/");
    }

    private static boolean internal(String path) {
        for (String segment : path.split("/")) {
            if (segment.startsWith(".") || segment.equalsIgnoreCase("AGENTS.md")) {
                return true;
            }
        }
        return false;
    }

    private static String relative(String document, String target) {
        String value = Path.of(document)
                .getParent()
                .relativize(Path.of(target))
                .toString()
                .replace('\\', '/');
        var encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = Byte.toUnsignedInt(b);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~/".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
            }
        }
        return encoded.toString();
    }

    private static String suffix(String path) {
        int dot = path.lastIndexOf('.');
        String suffix = dot < 0 ? "" : path.substring(dot).toLowerCase(Locale.ROOT);
        return suffix.matches("\\.[a-z0-9]{1,10}") ? suffix : ".bin";
    }

    private static String extension(String type) {
        return switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "application/pdf" -> ".pdf";
            case "audio/mpeg" -> ".mp3";
            case "audio/ogg" -> ".ogg";
            case "video/mp4" -> ".mp4";
            default -> ".bin";
        };
    }

    private static String publicBody(String body, UnaryOperator<String> destination) {
        Node root = Parser.builder().build().parse(body);
        var nodes = new ArrayList<Node>();
        record Visit(Node node, int depth) {}
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(root, 0));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (nodes.size() >= 20_000 || visit.depth() > 128) {
                throw unavailable();
            }
            nodes.add(visit.node());
            for (Node child = visit.node().getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(new Visit(child, visit.depth() + 1));
            }
        }
        for (Node node : nodes) {
            if (node instanceof HtmlBlock || node instanceof HtmlInline || node instanceof LinkReferenceDefinition) {
                node.unlink();
            } else if (node instanceof Link link) {
                if (link.getDestination().startsWith("managed:")) {
                    throw unavailable();
                }
                String target = destination.apply(link.getDestination());
                if (safeDestination(target)) {
                    link.setDestination(target);
                } else {
                    flatten(node);
                }
            } else if (node instanceof Image image) {
                String target = destination.apply(image.getDestination());
                if (safeDestination(target)) {
                    image.setDestination(target);
                } else {
                    flatten(node);
                }
            }
        }
        return MarkdownRenderer.builder().build().render(root);
    }

    private static boolean safeDestination(String value) {
        return !value.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*") || value.matches("(?is)^(https?|mailto):.*");
    }

    private static void flatten(Node node) {
        while (node.getFirstChild() != null) {
            node.insertBefore(node.getFirstChild());
        }
        node.unlink();
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("selected export content is unavailable or exceeds its bounds");
    }
}
