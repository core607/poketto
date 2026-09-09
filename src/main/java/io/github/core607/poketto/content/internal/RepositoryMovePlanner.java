package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;

/** Builds one candidate from immutable base objects. It neither fetches media nor advances authority. */
final class RepositoryMovePlanner {
    static RepositoryCandidateChanges prepare(
            Repository repository,
            DirCache index,
            RepositoryMoveRequest request,
            RepositoryPublishingPolicy policy,
            RepositoryMediaIndex media)
            throws IOException {
        if (policy.state() == RepositoryPublishingPolicy.State.INVALID)
            throw new IllegalArgumentException("repair the publication policy before moving content");
        Map<String, RepositoryCandidateChanges.ObjectEntry> files = new LinkedHashMap<>();
        for (int i = 0; i < index.getEntryCount(); i++) {
            var entry = index.getEntry(i);
            files.put(
                    entry.getPathString(),
                    new RepositoryCandidateChanges.ObjectEntry(
                            entry.getObjectId().copy(), entry.getFileMode()));
        }
        media.requireNoGitCollisions(files.keySet());
        Set<String> namespace = new HashSet<>(files.keySet());
        namespace.addAll(media.files().keySet());
        Map<String, String> relocated = relocate(namespace, request);
        Map<String, RepositoryCandidateChanges.ObjectEntry> copies = new LinkedHashMap<>();
        Set<String> deletions = new HashSet<>();
        for (var move : relocated.entrySet()) {
            var entry = files.get(move.getKey());
            if (entry == null) continue;
            if (!regular(entry.mode()))
                throw new IllegalArgumentException("moves cannot include symlinks or submodules");
            copies.put(move.getValue(), entry);
            deletions.add(move.getKey());
        }
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        Map<String, RepositoryMediaIndex.Media> mappedMedia = new HashMap<>();
        media.files().forEach((path, original) -> mappedMedia.put(relocated.getOrDefault(path, path), original));
        if (!mappedMedia.equals(media.files()))
            replacements.put(RepositoryMediaIndex.PATH, new RepositoryMediaIndex(mappedMedia).encode());

        long total = 0;
        int documents = 0;
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, RepositoryMarkdownParser.Metadata> parsed = new LinkedHashMap<>();
        Map<String, String> routes = new HashMap<>();
        Set<String> ambiguousRoutes = new HashSet<>();
        var parser = new RepositoryMarkdownParser();
        for (var file : files.entrySet()) {
            String path = file.getKey();
            if (!RepositoryPathRules.markdown(path)
                    || RepositoryPathRules.reserved(path)
                    || !regular(file.getValue().mode())) continue;
            var blob = repository.open(file.getValue().objectId(), Constants.OBJ_BLOB);
            total += blob.getSize();
            if (++documents > ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE
                    || total > ContentLimits.MAX_WORKSPACE_BYTES
                    || blob.getSize() > ContentLimits.MAX_DOCUMENT_BYTES)
                throw new IllegalArgumentException("move reference repair exceeds workspace text bounds");
            String source = RepositoryMarkdownParser.decode(blob.getBytes(ContentLimits.MAX_DOCUMENT_BYTES));
            var metadata = parser.parse(path, source);
            sources.put(path, source);
            parsed.put(path, metadata);
            if (routes.putIfAbsent(metadata.route(), path) != null) ambiguousRoutes.add(metadata.route());
        }
        ambiguousRoutes.forEach(routes::remove);
        for (var document : sources.entrySet()) {
            String oldPath = document.getKey();
            String newPath = relocated.getOrDefault(oldPath, oldPath);
            String source = document.getValue();
            String body = parsed.get(oldPath).body();
            String repaired = MarkdownLinkRewriter.rewrite(body, authored -> {
                var resolved = MarkdownDestinations.path(oldPath, authored);
                if (resolved.isEmpty()) return authored;
                String target = resolved.orElseThrow();
                if (!namespace.contains(target)) target = routes.get("/" + target);
                if (target == null) return authored;
                String newTarget = relocated.getOrDefault(target, target);
                if (!newPath.equals(oldPath) || !newTarget.equals(target)) {
                    if (policy.permitsPath(newPath)
                            && (!policy.permitsPath(newTarget)
                                    || files.containsKey(target)
                                            && !regular(files.get(target).mode())))
                        throw new IllegalArgumentException(
                                "move would leave a public document referencing private content");
                    String fragment = authored.contains("#") ? authored.substring(authored.indexOf('#')) : "";
                    return relative(newPath, newTarget) + fragment;
                }
                return authored;
            });
            if (!repaired.equals(body)) {
                String replacement = source.substring(0, source.length() - body.length()) + repaired;
                byte[] bytes = replacement.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > ContentLimits.MAX_DOCUMENT_BYTES)
                    throw new IllegalArgumentException("repaired document exceeds its byte limit");
                parser.parse(newPath, replacement);
                replacements.put(newPath, bytes);
            } else if (!newPath.equals(oldPath)) {
                parser.parse(newPath, source);
            }
        }
        return new RepositoryCandidateChanges(replacements, copies, deletions, true);
    }

    private static Map<String, String> relocate(Set<String> namespace, RepositoryMoveRequest request) {
        Map<String, String> moved = new LinkedHashMap<>();
        String source = request.source();
        String destination = request.destination();
        String sourceKey = DocumentPathRules.collisionKey(source);
        String destinationKey = DocumentPathRules.collisionKey(destination);
        if (destinationKey.startsWith(sourceKey + "/") || sourceKey.startsWith(destinationKey + "/"))
            throw new IllegalArgumentException("move paths must not contain each other");
        for (String path : namespace) {
            if (path.equals(source) || path.startsWith(source + "/")) {
                if (RepositoryPathRules.reserved(path))
                    throw new IllegalArgumentException("move cannot include repository metadata");
                String target = RepositoryPathRules.validate(destination + path.substring(source.length()));
                if (RepositoryPathRules.markdown(path) != RepositoryPathRules.markdown(target))
                    throw new IllegalArgumentException("move must preserve the Markdown file type");
                moved.put(path, target);
            }
        }
        if (moved.isEmpty()) throw new IllegalArgumentException("move source does not exist");
        TreeSet<String> finalPaths = new TreeSet<>();
        for (String path : namespace) {
            String key = DocumentPathRules.collisionKey(path);
            if (!moved.containsKey(path) && (key.equals(destinationKey) || key.startsWith(destinationKey + "/")))
                throw new IllegalArgumentException("move destination already exists; directories are not merged");
            String newKey = DocumentPathRules.collisionKey(moved.getOrDefault(path, path));
            if (!finalPaths.add(newKey)) throw new IllegalArgumentException("move creates a path collision");
        }
        for (String key : finalPaths) {
            for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
                if (finalPaths.contains(key.substring(0, slash)))
                    throw new IllegalArgumentException("move creates a file/directory collision");
            }
        }
        return moved;
    }

    private static boolean regular(FileMode mode) {
        return mode.equals(FileMode.REGULAR_FILE) || mode.equals(FileMode.EXECUTABLE_FILE);
    }

    private static String relative(String document, String target) {
        String[] from = document.split("/");
        String[] to = target.split("/");
        int common = 0;
        while (common < from.length - 1 && common < to.length && from[common].equals(to[common])) common++;
        var segments = new ArrayList<String>();
        for (int i = common; i < from.length - 1; i++) segments.add("..");
        for (int i = common; i < to.length; i++) segments.add(to[i]);
        String path = String.join("/", segments);
        StringBuilder encoded = new StringBuilder();
        for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
            int c = Byte.toUnsignedInt(value);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~/".indexOf(c) >= 0)
                encoded.append((char) c);
            else encoded.append('%').append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
        }
        return encoded.toString();
    }
}
