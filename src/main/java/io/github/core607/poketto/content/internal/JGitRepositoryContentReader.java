package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryDirectoryPage;
import io.github.core607.poketto.content.RepositoryDocument;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryTree;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitRepositoryContentReader implements RepositoryContentReader {
    private static final int MAX_TREE_ENTRIES = 100_000;
    private final RepositoryAuthority authority;
    private final RepositoryMarkdownParser parser = new RepositoryMarkdownParser();

    JGitRepositoryContentReader(RepositoryAuthority authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @Override
    public RepositoryTree readTree(WorkspaceId workspaceId, Optional<String> commit) {
        return resolve(
                workspaceId,
                commit,
                (repository, resolved) -> readTreeObjects(workspaceId, repository, resolved, path -> true));
    }

    @Override
    public RepositoryDirectoryPage listDirectory(
            WorkspaceId workspaceId, Optional<String> commit, String path, int offset, int limit) {
        Objects.requireNonNull(path);
        if (!path.isEmpty()) {
            RepositoryPathRules.validate(path);
        }
        if (offset < 0 || offset > MAX_TREE_ENTRIES || limit < 1 || limit > 200 || (offset > 0 && commit.isEmpty())) {
            throw new IllegalArgumentException("directory pages require valid bounds and a pinned continuation commit");
        }
        return resolve(workspaceId, commit, (repository, resolved) -> {
            if (resolved.isEmpty()) {
                return new RepositoryDirectoryPage(workspaceId, resolved, path, !path.isEmpty(), List.of(), null);
            }
            try (RevWalk revisions = new RevWalk(repository);
                    TreeWalk children = new TreeWalk(repository)) {
                ObjectId tree = revisions
                        .parseCommit(ObjectId.fromString(resolved.orElseThrow()))
                        .getTree();
                RepositoryMediaIndex media = readMediaIndex(repository, tree);
                if (!media.files().isEmpty()) {
                    return logicalDirectory(repository, workspaceId, resolved, tree, path, offset, limit, media);
                }
                if (!path.isEmpty()) {
                    try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
                        if (entry == null) {
                            return new RepositoryDirectoryPage(workspaceId, resolved, path, true, List.of(), null);
                        }
                        if (!FileMode.TREE.equals(entry.getFileMode(0))) {
                            throw new IllegalArgumentException("requested path is not a directory");
                        }
                        tree = entry.getObjectId(0);
                    }
                }
                children.addTree(tree);
                List<RepositoryDirectoryPage.Entry> entries = new ArrayList<>();
                int index = 0;
                Integer nextOffset = null;
                while (children.next()) {
                    if (++index <= offset) {
                        continue;
                    }
                    if (entries.size() == limit) {
                        nextOffset = offset + entries.size();
                        if (nextOffset > MAX_TREE_ENTRIES) {
                            throw new ContentRepositoryException("directory continuation exceeds the maximum offset");
                        }
                        break;
                    }
                    if (children.getPathLength() > ContentLimits.MAX_PATH_LENGTH * 4) {
                        throw new ContentRepositoryException("directory entry exceeds the repository path bound");
                    }
                    String childPath =
                            path.isEmpty() ? children.getPathString() : path + "/" + children.getPathString();
                    if (childPath.length() > ContentLimits.MAX_PATH_LENGTH) {
                        throw new ContentRepositoryException("directory entry exceeds the repository path bound");
                    }
                    entries.add(new RepositoryDirectoryPage.Entry(childPath, kind(children.getFileMode(0))));
                }
                return new RepositoryDirectoryPage(workspaceId, resolved, path, false, entries, nextOffset);
            }
        });
    }

    private static RepositoryDirectoryPage.Kind kind(FileMode mode) {
        if (FileMode.TREE.equals(mode)) {
            return RepositoryDirectoryPage.Kind.DIRECTORY;
        }
        if (FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode)) {
            return RepositoryDirectoryPage.Kind.FILE;
        }
        if (FileMode.SYMLINK.equals(mode)) {
            return RepositoryDirectoryPage.Kind.SYMLINK;
        }
        if (FileMode.GITLINK.equals(mode)) {
            return RepositoryDirectoryPage.Kind.SUBMODULE;
        }
        return RepositoryDirectoryPage.Kind.OTHER;
    }

    static RepositoryMediaIndex readMediaIndex(Repository repository, ObjectId tree) throws IOException {
        try (TreeWalk entry = TreeWalk.forPath(repository, RepositoryMediaIndex.PATH, tree)) {
            if (entry == null) {
                return RepositoryMediaIndex.empty();
            }
            if (!FileMode.REGULAR_FILE.equals(entry.getFileMode(0))) {
                throw new ContentRepositoryException("repository media index is not a regular file");
            }
            ObjectLoader blob = repository.open(entry.getObjectId(0), Constants.OBJ_BLOB);
            if (blob.getSize() > RepositoryMediaIndex.MAX_BYTES) {
                throw new ContentRepositoryException("repository media index exceeds its byte limit");
            }
            try {
                return RepositoryMediaIndex.parse(blob.getBytes(RepositoryMediaIndex.MAX_BYTES));
            } catch (IllegalArgumentException exception) {
                throw new ContentRepositoryException("repository media index is invalid");
            }
        }
    }

    private static RepositoryDirectoryPage logicalDirectory(
            Repository repository,
            WorkspaceId workspace,
            Optional<String> commit,
            ObjectId tree,
            String path,
            int offset,
            int limit,
            RepositoryMediaIndex media)
            throws IOException {
        boolean exists = path.isEmpty();
        if (!path.isEmpty()) {
            try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
                if (entry != null) {
                    if (!FileMode.TREE.equals(entry.getFileMode(0))) {
                        throw new IllegalArgumentException("requested path is not a directory");
                    }
                    exists = true;
                }
            }
            if (media.files().containsKey(path)) {
                throw new IllegalArgumentException("requested path is not a directory");
            }
        }
        Map<String, RepositoryDirectoryPage.Entry> children = new HashMap<>();
        List<String> gitPaths = new ArrayList<>();
        try (TreeWalk entries = new TreeWalk(repository)) {
            entries.addTree(tree);
            entries.setRecursive(true);
            while (entries.next()) {
                if (gitPaths.size() == MAX_TREE_ENTRIES) {
                    throw new ContentRepositoryException("logical directory exceeds the repository entry bound");
                }
                if (entries.getPathLength() > ContentLimits.MAX_PATH_LENGTH * 4) {
                    throw new ContentRepositoryException("repository entry exceeds the path bound");
                }
                String entryPath = entries.getPathString();
                gitPaths.add(entryPath);
                addImmediate(children, path, entryPath, kind(entries.getFileMode(0)));
            }
        }
        try {
            media.requireNoGitCollisions(gitPaths);
        } catch (IllegalArgumentException exception) {
            throw new ContentRepositoryException("repository media paths collide with Git entries");
        }
        for (String entryPath : media.files().keySet()) {
            addImmediate(children, path, entryPath, RepositoryDirectoryPage.Kind.FILE);
        }
        List<RepositoryDirectoryPage.Entry> ordered = new ArrayList<>(children.values());
        ordered.sort((a, b) -> Arrays.compareUnsigned(directorySortKey(a), directorySortKey(b)));
        exists |= !ordered.isEmpty();
        int end = (int) Math.min(ordered.size(), (long) offset + limit);
        Integer next = end < ordered.size() ? end : null;
        if (next != null && next > MAX_TREE_ENTRIES) {
            throw new ContentRepositoryException("directory continuation exceeds the maximum offset");
        }
        return new RepositoryDirectoryPage(
                workspace,
                commit,
                path,
                !exists,
                offset >= ordered.size() ? List.of() : ordered.subList(offset, end),
                next);
    }

    private static byte[] directorySortKey(RepositoryDirectoryPage.Entry entry) {
        return (entry.path() + (entry.kind() == RepositoryDirectoryPage.Kind.DIRECTORY ? "/" : ""))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void addImmediate(
            Map<String, RepositoryDirectoryPage.Entry> entries,
            String parent,
            String path,
            RepositoryDirectoryPage.Kind kind) {
        String prefix = parent.isEmpty() ? "" : parent + "/";
        if (!path.startsWith(prefix)) {
            return;
        }
        String relative = path.substring(prefix.length());
        int slash = relative.indexOf('/');
        String child = slash < 0 ? path : prefix + relative.substring(0, slash);
        if (child.length() > ContentLimits.MAX_PATH_LENGTH) {
            throw new ContentRepositoryException("directory entry exceeds the repository path bound");
        }
        entries.putIfAbsent(
                child,
                new RepositoryDirectoryPage.Entry(child, slash < 0 ? kind : RepositoryDirectoryPage.Kind.DIRECTORY));
    }

    /** Caller holds the authority lock and supplies a server-resolved snapshot. Does not fetch. */
    RepositoryTree readSnapshot(WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot) {
        return readSnapshot(workspaceId, snapshot, path -> true);
    }

    RepositoryTree readSnapshot(
            WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot, Predicate<String> eligible) {
        try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspaceId)) {
            return readTreeObjects(workspaceId, repository, snapshot.commitId(), eligible);
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository snapshot objects cannot be read", exception);
        }
    }

    private RepositoryTree readTreeObjects(
            WorkspaceId workspaceId, Repository repository, Optional<String> resolved, Predicate<String> eligible)
            throws IOException {
        if (resolved.isEmpty()) {
            return new RepositoryTree(workspaceId, resolved, List.of(), List.of());
        }
        List<RepositoryDocument> documents = new ArrayList<>();
        List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        long total = 0;
        List<ParsedDocument> parsed = new ArrayList<>();
        int entries = 0;
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(revisions
                    .parseCommit(ObjectId.fromString(resolved.orElseThrow()))
                    .getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                if (++entries > MAX_TREE_ENTRIES) {
                    throw new ContentRepositoryException("repository tree entry limit exceeded");
                }
                String path = tree.getPathString();
                if (!RepositoryPathRules.markdown(path) || RepositoryPathRules.reserved(path) || !eligible.test(path)) {
                    continue;
                }
                if (paths.size() >= ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) {
                    throw new ContentRepositoryException("repository Markdown count limit exceeded");
                }
                paths.add(path);
                FileMode mode = tree.getFileMode(0);
                if (FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode)) {
                    long size = tree.getObjectReader().getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                    if (size <= ContentLimits.MAX_DOCUMENT_BYTES) {
                        total += size;
                        if (total > ContentLimits.MAX_WORKSPACE_BYTES) {
                            throw new ContentRepositoryException("repository text byte limit exceeded");
                        }
                    }
                }
                RepositoryFile file = readFile(repository, workspaceId, resolved, path);
                diagnostics.addAll(file.diagnostics());
                if (file.source().isEmpty()) {
                    continue;
                }
                if (!file.diagnostics().isEmpty()) {
                    continue;
                }
                try {
                    var metadata = parser.parse(path, file.source().orElseThrow());
                    parsed.add(new ParsedDocument(file, metadata));
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(diagnostic(path, "INVALID_MARKDOWN", exception.getMessage()));
                }
            }
        }
        List<String> fallbackPaths = parsed.stream()
                .filter(document -> document.metadata().createdAt().isEmpty()
                        || document.metadata().updatedAt().isEmpty())
                .map(document -> document.file().path())
                .toList();
        var history = new RepositoryHistoryDates().read(repository, resolved.orElseThrow(), fallbackPaths);
        for (ParsedDocument document : parsed) {
            RepositoryFile file = document.file();
            var metadata = document.metadata();
            var dates = history.get(file.path());
            Instant createdAt = metadata.createdAt().orElseGet(() -> dates.createdAt());
            Instant updatedAt = metadata.updatedAt().orElseGet(() -> dates.updatedAt());
            if (metadata.inferredMetadata()) {
                diagnostics.add(
                        diagnostic(file.path(), "INFERRED_METADATA", "title and dates use repository fallbacks"));
            }
            documents.add(new RepositoryDocument(
                    file,
                    metadata.title(),
                    metadata.body(),
                    metadata.tags(),
                    createdAt,
                    updatedAt,
                    metadata.route(),
                    RepositoryPathRules.folderPage(file.path()),
                    RepositoryPathRules.privatePath(file.path())));
        }
        Set<String> excluded = collisions(paths, documents, diagnostics);
        documents.removeIf(document -> excluded.contains(document.file().path()));
        documents.sort(Comparator.comparing(document -> document.file().path()));
        diagnostics.sort(Comparator.comparing(RepositoryDiagnostic::path).thenComparing(RepositoryDiagnostic::code));
        return new RepositoryTree(workspaceId, resolved, documents, diagnostics);
    }

    @Override
    public RepositoryFile getFile(WorkspaceId workspaceId, Optional<String> commit, String path) {
        RepositoryPathRules.validate(path);
        return resolve(
                workspaceId, commit, (repository, resolved) -> readFile(repository, workspaceId, resolved, path));
    }

    private RepositoryFile readFile(
            Repository repository, WorkspaceId workspaceId, Optional<String> commit, String path) throws IOException {
        try {
            RepositoryPathRules.validate(path);
        } catch (IllegalArgumentException exception) {
            return invalid(workspaceId, commit, path, "INVALID_PATH", exception.getMessage());
        }
        if (commit.isEmpty()) {
            return absent(workspaceId, commit, path);
        }
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk entry = TreeWalk.forPath(
                        repository,
                        path,
                        revisions
                                .parseCommit(ObjectId.fromString(commit.orElseThrow()))
                                .getTree())) {
            if (entry == null) {
                ObjectId tree = revisions
                        .parseCommit(ObjectId.fromString(commit.orElseThrow()))
                        .getTree();
                if (readMediaIndex(repository, tree).files().containsKey(path)) {
                    return invalid(
                            workspaceId,
                            commit,
                            path,
                            "MANAGED_MEDIA",
                            "path is an indexed media file; fetch its original through the media entrance");
                }
                return absent(workspaceId, commit, path);
            }
            FileMode mode = entry.getFileMode(0);
            if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode)) {
                return invalid(workspaceId, commit, path, "NOT_REGULAR_FILE", "path is not a regular file");
            }
            ObjectLoader loader = repository.open(entry.getObjectId(0), Constants.OBJ_BLOB);
            if (loader.getSize() > ContentLimits.MAX_DOCUMENT_BYTES) {
                return invalid(workspaceId, commit, path, "FILE_TOO_LARGE", "file exceeds the text byte limit");
            }
            byte[] bytes = loader.getBytes(ContentLimits.MAX_DOCUMENT_BYTES);
            DocumentRevision revision = DocumentRevision.sha256(bytes);
            try {
                String source = RepositoryMarkdownParser.decode(bytes);
                List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
                if (RepositoryPathRules.markdown(path)) {
                    try {
                        parser.parse(path, source);
                    } catch (IllegalArgumentException exception) {
                        diagnostics.add(diagnostic(path, "INVALID_MARKDOWN", exception.getMessage()));
                    }
                }
                return new RepositoryFile(
                        workspaceId, commit, path, false, Optional.of(source), Optional.of(revision), diagnostics);
            } catch (IllegalArgumentException exception) {
                return new RepositoryFile(
                        workspaceId,
                        commit,
                        path,
                        false,
                        Optional.empty(),
                        Optional.of(revision),
                        List.of(diagnostic(path, "INVALID_UTF8", exception.getMessage())));
            }
        }
    }

    private <T> T resolve(WorkspaceId workspaceId, Optional<String> requested, Reader<T> reader) {
        Objects.requireNonNull(workspaceId);
        Objects.requireNonNull(requested);
        requested.ifPresent(commit -> {
            if (!commit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("commit must be an exact lowercase object id");
            }
        });
        return authority.readObjects(workspaceId, snapshot -> {
            try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspaceId)) {
                Optional<String> selected = requested.isPresent() ? requested : snapshot.commitId();
                if (requested.isPresent()) {
                    if (snapshot.commitId().isEmpty()
                            || !reachable(repository, snapshot.commitId().orElseThrow(), requested.orElseThrow())) {
                        throw new IllegalArgumentException("requested commit is not in remote main history");
                    }
                }
                return reader.read(repository, selected);
            } catch (IOException exception) {
                throw new ContentRepositoryException("repository objects cannot be read", exception);
            }
        });
    }

    private static boolean reachable(Repository repository, String head, String candidate) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
            int count = 0;
            for (RevCommit commit : walk) {
                if (++count > RepositoryHistoryDates.MAX_COMMITS) {
                    throw new ContentRepositoryException("repository history limit exceeded");
                }
                if (commit.name().equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Set<String> collisions(
            List<String> paths, List<RepositoryDocument> documents, List<RepositoryDiagnostic> diagnostics) {
        Set<String> excluded = new HashSet<>();
        Map<String, List<String>> byPath = new HashMap<>();
        paths.forEach(path -> byPath.computeIfAbsent(DocumentPathRules.collisionKey(path), ignored -> new ArrayList<>())
                .add(path));
        byPath.values().stream()
                .filter(group -> group.size() > 1)
                .forEach(group -> group.forEach(path -> {
                    excluded.add(path);
                    diagnostics.add(
                            diagnostic(path, "PATH_COLLISION", "path collides after normalization and case folding"));
                }));
        Map<String, List<String>> byRoute = new HashMap<>();
        documents.forEach(document -> byRoute.computeIfAbsent(
                        DocumentPathRules.collisionKey(document.route()), ignored -> new ArrayList<>())
                .add(document.file().path()));
        byRoute.values().stream()
                .filter(group -> group.size() > 1)
                .forEach(group -> group.forEach(path -> {
                    excluded.add(path);
                    diagnostics.add(diagnostic(path, "ROUTE_COLLISION", "route is claimed by multiple Markdown files"));
                }));
        return excluded;
    }

    private static RepositoryDiagnostic diagnostic(String path, String code, String message) {
        return new RepositoryDiagnostic(path, code, message);
    }

    private static RepositoryFile absent(WorkspaceId workspace, Optional<String> commit, String path) {
        return new RepositoryFile(workspace, commit, path, true, Optional.empty(), Optional.empty(), List.of());
    }

    private static RepositoryFile invalid(
            WorkspaceId workspace, Optional<String> commit, String path, String code, String message) {
        return new RepositoryFile(
                workspace,
                commit,
                path,
                false,
                Optional.empty(),
                Optional.empty(),
                List.of(diagnostic(path, code, message)));
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(Repository repository, Optional<String> commit) throws IOException;
    }

    private record ParsedDocument(RepositoryFile file, RepositoryMarkdownParser.Metadata metadata) {}
}
