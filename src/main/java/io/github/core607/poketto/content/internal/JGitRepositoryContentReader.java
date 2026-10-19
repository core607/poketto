package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryDirectoryPage;
import io.github.core607.poketto.content.RepositoryDocument;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryFilenamePage;
import io.github.core607.poketto.content.RepositoryFilenameSearch;
import io.github.core607.poketto.content.RepositoryHistoryPage;
import io.github.core607.poketto.content.RepositoryHistoryQuery;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySyncEntry;
import io.github.core607.poketto.content.RepositoryTree;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitRepositoryContentReader implements RepositoryContentReader {
    private final RepositoryAuthority authority;
    private final RepositoryDocumentScanner scanner = new RepositoryDocumentScanner();

    JGitRepositoryContentReader(RepositoryAuthority authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @Override
    public Optional<String> currentCommit(WorkspaceId workspace) {
        return authority.readObjects(workspace, RepositoryAuthority.Snapshot::commitId);
    }

    @Override
    public RepositoryHistoryPage history(WorkspaceId workspace, Optional<String> commit, RepositoryHistoryQuery query) {
        if (query.offset() > 0 && commit.isEmpty()) {
            throw new IllegalArgumentException("history continuation requires a pinned commit");
        }
        return resolve(
                workspace, commit, (repository, selected) -> JGitRepositoryHistory.read(repository, selected, query));
    }

    @Override
    public RepositoryTree readTree(WorkspaceId workspaceId, Optional<String> commit) {
        return resolve(
                workspaceId,
                commit,
                (repository, resolved) -> readTreeObjects(workspaceId, repository, resolved, path -> true));
    }

    @Override
    public RepositoryFilenamePage searchFilenames(
            WorkspaceId workspace, Optional<String> commit, RepositoryFilenameSearch search) {
        requireFilenameCommit(commit, search);
        return resolve(
                workspace,
                commit,
                (repository, resolved) ->
                        JGitRepositoryFilenameSearch.search(repository, resolved, search, path -> true));
    }

    @Override
    public RepositoryFilenamePage searchPublicFilenames(
            WorkspaceId workspace, Optional<String> commit, RepositoryFilenameSearch search) {
        requireFilenameCommit(commit, search);
        return resolveCurrent(workspace, commit, (repository, resolved) -> {
            var policy = PublicRepositoryDirectories.publicPolicy(repository, resolved);
            return JGitRepositoryFilenameSearch.search(repository, resolved, search, policy::permitsPath);
        });
    }

    private static void requireFilenameCommit(Optional<String> commit, RepositoryFilenameSearch search) {
        if (search.offset() > 0 && commit.isEmpty()) {
            throw new IllegalArgumentException("Filename search continuations require a pinned commit");
        }
    }

    @Override
    public RepositoryTree readPublicTree(WorkspaceId workspace, Optional<String> commit) {
        return resolveCurrent(workspace, commit, (repository, resolved) -> {
            var policy = PublicRepositoryDirectories.publicPolicy(repository, resolved);
            return readTreeObjects(workspace, repository, resolved, policy::permitsPath);
        });
    }

    @Override
    public RepositoryFile getPublicFile(WorkspaceId workspace, Optional<String> commit, String path) {
        RepositoryPathRules.validate(path);
        return resolveCurrent(workspace, commit, (repository, resolved) -> {
            if (!PublicRepositoryDirectories.publicPolicy(repository, resolved).permitsPath(path)) {
                throw denied();
            }
            return JGitRepositoryFileReader.read(repository, workspace, resolved, path, true);
        });
    }

    @Override
    public RepositoryDirectoryPage listPublicDirectory(
            WorkspaceId workspace, Optional<String> commit, String path, int offset, int limit) {
        Objects.requireNonNull(path);
        if (!path.isEmpty()) {
            RepositoryPathRules.validate(path);
        }
        if (offset < 0
                || offset > RepositoryDocumentScanner.MAX_TREE_ENTRIES
                || limit < 1
                || limit > 200
                || (offset > 0 && commit.isEmpty())) {
            throw new IllegalArgumentException("directory pages require valid bounds and a pinned continuation commit");
        }
        return resolveCurrent(
                workspace,
                commit,
                (repository, resolved) ->
                        PublicRepositoryDirectories.page(repository, workspace, resolved, path, offset, limit));
    }

    private static AuthException denied() {
        return new AuthException(AuthException.Code.DENIED);
    }

    @Override
    public RepositoryDirectoryPage listDirectory(
            WorkspaceId workspaceId, Optional<String> commit, String path, int offset, int limit) {
        Objects.requireNonNull(path);
        if (!path.isEmpty()) {
            RepositoryPathRules.validate(path);
        }
        if (offset < 0
                || offset > RepositoryDocumentScanner.MAX_TREE_ENTRIES
                || limit < 1
                || limit > 200
                || (offset > 0 && commit.isEmpty())) {
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
                    Optional<ObjectId> subtree = subtree(repository, tree, path);
                    if (subtree.isEmpty()) {
                        return new RepositoryDirectoryPage(workspaceId, resolved, path, true, List.of(), null);
                    }
                    tree = subtree.orElseThrow();
                }
                children.addTree(tree);
                return childPage(workspaceId, resolved, path, children, offset, limit);
            }
        });
    }

    private static Optional<ObjectId> subtree(Repository repository, ObjectId tree, String path) throws IOException {
        try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
            if (entry == null) {
                return Optional.empty();
            }
            if (!FileMode.TREE.equals(entry.getFileMode(0))) {
                throw new IllegalArgumentException("requested path is not a directory");
            }
            return Optional.of(entry.getObjectId(0));
        }
    }

    // One page of direct children; a continuation offset is returned only when more entries follow.
    private static RepositoryDirectoryPage childPage(
            WorkspaceId workspaceId, Optional<String> resolved, String path, TreeWalk children, int offset, int limit)
            throws IOException {
        List<RepositoryDirectoryPage.Entry> entries = new ArrayList<>();
        int index = 0;
        Integer nextOffset = null;
        while (children.next()) {
            if (++index <= offset) {
                continue;
            }
            if (entries.size() == limit) {
                nextOffset = offset + entries.size();
                if (nextOffset > RepositoryDocumentScanner.MAX_TREE_ENTRIES) {
                    throw new ContentRepositoryException("directory continuation exceeds the maximum offset");
                }
                break;
            }
            if (children.getPathLength() > ContentLimits.MAX_PATH_LENGTH * 4) {
                throw new ContentRepositoryException("directory entry exceeds the repository path bound");
            }
            String childPath = path.isEmpty() ? children.getPathString() : path + "/" + children.getPathString();
            if (childPath.length() > ContentLimits.MAX_PATH_LENGTH) {
                throw new ContentRepositoryException("directory entry exceeds the repository path bound");
            }
            entries.add(new RepositoryDirectoryPage.Entry(childPath, kind(children.getFileMode(0))));
        }
        return new RepositoryDirectoryPage(workspaceId, resolved, path, false, entries, nextOffset);
    }

    private static RepositoryDirectoryPage.Kind kind(FileMode mode) {
        if (FileMode.TREE.equals(mode)) {
            return RepositoryDirectoryPage.Kind.DIRECTORY;
        }
        if (RepositoryBlobs.isFile(mode)) {
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
        try (var objects = repository.newObjectReader()) {
            return readMediaIndex(objects, tree);
        }
    }

    static RepositoryMediaIndex readMediaIndex(ObjectReader objects, ObjectId tree) throws IOException {
        try (TreeWalk entry = TreeWalk.forPath(objects, RepositoryMediaIndex.PATH, tree)) {
            if (entry == null) {
                return RepositoryMediaIndex.empty();
            }
            if (!RepositoryBlobs.isPlainFile(entry.getFileMode(0))) {
                throw new ContentRepositoryException("repository media index is not a regular file");
            }
            ObjectLoader blob = objects.open(entry.getObjectId(0), Constants.OBJ_BLOB);
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
                if (gitPaths.size() == RepositoryDocumentScanner.MAX_TREE_ENTRIES) {
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
        if (next != null && next > RepositoryDocumentScanner.MAX_TREE_ENTRIES) {
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

    static void addImmediate(
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
        try (Repository repository = RepositoryCaches.openCache(snapshot.worktree(), workspaceId)) {
            return readTreeObjects(workspaceId, repository, snapshot.commitId(), path -> true);
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository snapshot objects cannot be read", exception);
        }
    }

    /** Drops the documents a public snapshot's read kept for this workspace. */
    void forgetSnapshot(WorkspaceId workspaceId) {
        scanner.forget(workspaceId);
    }

    /** The public snapshot's read: unchanged documents of its previous build are reused, not parsed again. */
    RepositoryTree readSnapshot(
            WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot, Predicate<String> eligible) {
        try (Repository repository = RepositoryCaches.openCache(snapshot.worktree(), workspaceId)) {
            return readTreeObjects(workspaceId, repository, snapshot.commitId(), eligible, true);
        } catch (IOException exception) {
            throw new ContentRepositoryException("repository snapshot objects cannot be read", exception);
        }
    }

    private RepositoryTree readTreeObjects(
            WorkspaceId workspaceId, Repository repository, Optional<String> resolved, Predicate<String> eligible)
            throws IOException {
        return readTreeObjects(workspaceId, repository, resolved, eligible, false);
    }

    private RepositoryTree readTreeObjects(
            WorkspaceId workspaceId,
            Repository repository,
            Optional<String> resolved,
            Predicate<String> eligible,
            boolean keep)
            throws IOException {
        if (resolved.isEmpty()) {
            return new RepositoryTree(workspaceId, resolved, List.of(), List.of());
        }
        var scan = scanner.scan(workspaceId, repository, resolved, eligible, keep);
        List<RepositoryDiagnostic> diagnostics = scan.diagnostics();
        List<String> fallbackPaths = scan.parsed().stream()
                .filter(document -> (document.metadata().createdAt().isEmpty()
                                && document.metadata()
                                        .release(document.file().path())
                                        .isEmpty())
                        || document.metadata().updatedAt().isEmpty())
                .map(document -> document.file().path())
                .toList();
        var history = new RepositoryHistoryDates().read(repository, resolved.orElseThrow(), fallbackPaths);
        List<RepositoryDocument> documents = new ArrayList<>();
        for (RepositoryDocumentScanner.Parsed parsed : scan.parsed()) {
            documents.add(document(parsed, history.get(parsed.file().path()), diagnostics));
        }
        FolderLandings.preferIndex(documents, diagnostics);
        Set<String> excluded = collisions(scan.paths(), documents, diagnostics);
        documents.removeIf(document -> excluded.contains(document.file().path()));
        ArticleIdentityDiagnostics.append(documents, diagnostics);
        documents.sort(Comparator.comparing(document -> document.file().path()));
        diagnostics.sort(Comparator.comparing(RepositoryDiagnostic::path).thenComparing(RepositoryDiagnostic::code));
        return new RepositoryTree(workspaceId, resolved, documents, diagnostics);
    }

    // Authored dates win; history dates fill the gaps and are reported as inferred metadata.
    private static RepositoryDocument document(
            RepositoryDocumentScanner.Parsed document,
            RepositoryHistoryDates.Dates dates,
            List<RepositoryDiagnostic> diagnostics) {
        RepositoryFile file = document.file();
        var metadata = document.metadata();
        Optional<Instant> release = metadata.release(file.path());
        Instant createdAt = metadata.createdAt().or(() -> release).orElseGet(() -> dates.createdAt());
        Instant updatedAt = metadata.updatedAt()
                .orElseGet(() -> release.filter(instant -> instant.isAfter(dates.updatedAt()))
                        .orElseGet(() -> dates.updatedAt()));
        if (metadata.invalidArticleId()) {
            diagnostics.add(
                    diagnostic(file.path(), "INVALID_ARTICLE_ID", "article id must be a canonical lowercase UUID"));
        }
        if (metadata.inferredMetadata()) {
            diagnostics.add(diagnostic(file.path(), "INFERRED_METADATA", "title and dates use repository fallbacks"));
        }
        return new RepositoryDocument(
                file,
                metadata.title(),
                metadata.body(),
                metadata.tags(),
                createdAt,
                updatedAt,
                metadata.route(),
                RepositoryPathRules.folderPage(file.path()),
                metadata.publicAuthor(),
                metadata.articleId(),
                metadata.featured(),
                release.orElse(null));
    }

    @Override
    public RepositoryFile getFile(WorkspaceId workspaceId, Optional<String> commit, String path) {
        RepositoryPathRules.validate(path);
        return resolve(
                workspaceId,
                commit,
                (repository, resolved) -> JGitRepositoryFileReader.read(
                        repository,
                        workspaceId,
                        resolved,
                        path,
                        PublicRepositoryDirectories.publicPolicy(repository, resolved)
                                .permitsPath(path)));
    }

    @Override
    public void visitBaseline(
            WorkspaceId workspace, String commit, RepositoryBaselineLimits limits, Consumer<RepositoryFile> sink) {
        Objects.requireNonNull(limits, "baseline limits must be present");
        Objects.requireNonNull(sink, "baseline sink must be present");
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        String selected = resolve(workspace, Optional.of(commit), (repository, pinned) -> pinned.orElseThrow());
        // The sink can compress and persist a whole baseline. Keep the cache pinned without holding its ref lock.
        authority.readImmutableObjects(workspace, objects -> {
            new JGitRepositoryBaselineReader(objects, workspace, selected, limits, deadline, sink).visit();
            return null;
        });
    }

    @Override
    public RepositorySyncEntry inspectBlob(WorkspaceId workspace, String commit, String path) {
        return resolve(
                workspace,
                Optional.of(commit),
                (repository, selected) -> JGitRepositorySyncReader.read(
                        repository, selected.orElseThrow(), path, OutputStream.nullOutputStream()));
    }

    @Override
    public void copyBlob(WorkspaceId workspace, RepositorySyncEntry blob, OutputStream output) {
        resolve(workspace, Optional.of(blob.commit()), (repository, selected) -> {
            RepositorySyncEntry copied =
                    JGitRepositorySyncReader.read(repository, selected.orElseThrow(), blob.path(), output);
            if (!copied.equals(blob) || copied.kind() != RepositorySyncEntry.Kind.FILE) {
                throw new IOException("repository blob differs from the staged selection");
            }
            return null;
        });
    }

    private <T> T resolve(WorkspaceId workspaceId, Optional<String> requested, Reader<T> reader) {
        return resolve(workspaceId, requested, reader, false);
    }

    private <T> T resolveCurrent(WorkspaceId workspaceId, Optional<String> requested, Reader<T> reader) {
        return resolve(workspaceId, requested, reader, true);
    }

    private <T> T resolve(WorkspaceId workspaceId, Optional<String> requested, Reader<T> reader, boolean currentOnly) {
        Objects.requireNonNull(workspaceId);
        Objects.requireNonNull(requested);
        requested.ifPresent(commit -> {
            if (!commit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("commit must be an exact lowercase object id");
            }
        });
        return authority.readObjects(workspaceId, snapshot -> {
            try (Repository repository = RepositoryCaches.openCache(snapshot.worktree(), workspaceId)) {
                Optional<String> selected = requested.isPresent() ? requested : snapshot.commitId();
                if (requested.isPresent()) {
                    if (currentOnly && !requested.equals(snapshot.commitId())) {
                        throw denied();
                    }
                    if (snapshot.commitId().isEmpty()
                            || !JGitRepositoryHistory.reachable(
                                    repository, snapshot.commitId().orElseThrow(), requested.orElseThrow())) {
                        throw new IllegalArgumentException("requested commit is not in remote main history");
                    }
                }
                return reader.read(repository, selected);
            } catch (IOException exception) {
                throw new ContentRepositoryException("repository objects cannot be read", exception);
            }
        });
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

    @FunctionalInterface
    private interface Reader<T> {
        T read(Repository repository, Optional<String> commit) throws IOException;
    }
}
