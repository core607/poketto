package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryDocument;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryTree;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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
import org.eclipse.jgit.treewalk.filter.PathFilter;

final class JGitRepositoryContentReader implements RepositoryContentReader {
    private static final int MAX_HISTORY_COMMITS = 100_000;
    private static final long MAX_HISTORY_PATH_VISITS = 1_000_000;
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
        if (resolved.isEmpty()) return new RepositoryTree(workspaceId, resolved, List.of(), List.of());
        List<RepositoryDocument> documents = new ArrayList<>();
        List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        long total = 0;
        long historyVisits = 0;
        int historySize = historySize(repository, resolved.orElseThrow());
        int entries = 0;
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(revisions
                    .parseCommit(ObjectId.fromString(resolved.orElseThrow()))
                    .getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                if (++entries > MAX_TREE_ENTRIES)
                    throw new ContentRepositoryException("repository tree entry limit exceeded");
                String path = tree.getPathString();
                if (!RepositoryPathRules.markdown(path) || RepositoryPathRules.reserved(path) || !eligible.test(path))
                    continue;
                if (paths.size() >= ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE)
                    throw new ContentRepositoryException("repository Markdown count limit exceeded");
                paths.add(path);
                FileMode mode = tree.getFileMode(0);
                if (FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode)) {
                    long size = tree.getObjectReader().getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                    if (size <= ContentLimits.MAX_DOCUMENT_BYTES) {
                        total += size;
                        if (total > ContentLimits.MAX_WORKSPACE_BYTES)
                            throw new ContentRepositoryException("repository text byte limit exceeded");
                    }
                }
                RepositoryFile file = readFile(repository, workspaceId, resolved, path);
                diagnostics.addAll(file.diagnostics());
                if (file.source().isEmpty()) continue;
                if (!file.diagnostics().isEmpty()) continue;
                try {
                    var metadata = parser.parse(path, file.source().orElseThrow());
                    History dates;
                    if (metadata.createdAt().isPresent() && metadata.updatedAt().isPresent()) {
                        dates = new History(
                                metadata.createdAt().orElseThrow(),
                                metadata.updatedAt().orElseThrow());
                    } else {
                        // A path-filtered walk can visit commits that it never returns. Charge the
                        // complete reachable history before starting each walk to bound that work.
                        historyVisits += historySize;
                        if (historyVisits > MAX_HISTORY_PATH_VISITS)
                            throw new ContentRepositoryException("repository date lookup work limit exceeded");
                        dates = history(repository, resolved.orElseThrow(), path);
                    }
                    Instant createdAt = metadata.createdAt().orElse(dates.createdAt());
                    Instant updatedAt = metadata.updatedAt().orElse(dates.updatedAt());
                    if (metadata.inferredMetadata())
                        diagnostics.add(
                                diagnostic(path, "INFERRED_METADATA", "title and dates use repository fallbacks"));
                    documents.add(new RepositoryDocument(
                            file,
                            metadata.title(),
                            metadata.body(),
                            metadata.tags(),
                            createdAt,
                            updatedAt,
                            metadata.route(),
                            RepositoryPathRules.folderPage(path),
                            RepositoryPathRules.privatePath(path)));
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(diagnostic(path, "INVALID_MARKDOWN", exception.getMessage()));
                }
            }
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
        if (commit.isEmpty()) return absent(workspaceId, commit, path);
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk entry = TreeWalk.forPath(
                        repository,
                        path,
                        revisions
                                .parseCommit(ObjectId.fromString(commit.orElseThrow()))
                                .getTree())) {
            if (entry == null) return absent(workspaceId, commit, path);
            FileMode mode = entry.getFileMode(0);
            if (!FileMode.REGULAR_FILE.equals(mode) && !FileMode.EXECUTABLE_FILE.equals(mode))
                return invalid(workspaceId, commit, path, "NOT_REGULAR_FILE", "path is not a regular file");
            ObjectLoader loader = repository.open(entry.getObjectId(0), Constants.OBJ_BLOB);
            if (loader.getSize() > ContentLimits.MAX_DOCUMENT_BYTES)
                return invalid(workspaceId, commit, path, "FILE_TOO_LARGE", "file exceeds the text byte limit");
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
            if (!commit.matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("commit must be an exact lowercase object id");
        });
        return authority.readObjects(workspaceId, snapshot -> {
            try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspaceId)) {
                Optional<String> selected = requested.isPresent() ? requested : snapshot.commitId();
                if (requested.isPresent()) {
                    if (snapshot.commitId().isEmpty()
                            || !reachable(repository, snapshot.commitId().orElseThrow(), requested.orElseThrow()))
                        throw new IllegalArgumentException("requested commit is not in remote main history");
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
                if (++count > MAX_HISTORY_COMMITS)
                    throw new ContentRepositoryException("repository history limit exceeded");
                if (commit.name().equals(candidate)) return true;
            }
            return false;
        }
    }

    private static int historySize(Repository repository, String head) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
            int count = 0;
            for (RevCommit ignored : walk) {
                if (++count > MAX_HISTORY_COMMITS)
                    throw new ContentRepositoryException("repository history limit exceeded");
            }
            return count;
        }
    }

    private static History history(Repository repository, String commit, String path) throws IOException {
        Instant first = null;
        Instant last = null;
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setTreeFilter(org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(
                    PathFilter.create(path), org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF));
            walk.markStart(walk.parseCommit(ObjectId.fromString(commit)));
            int count = 0;
            for (RevCommit item : walk) {
                if (++count > MAX_HISTORY_COMMITS)
                    throw new ContentRepositoryException("repository history limit exceeded");
                Instant at = item.getCommitterIdent().getWhenAsInstant();
                if (first == null || at.isBefore(first)) first = at;
                if (last == null || at.isAfter(last)) last = at;
            }
        }
        if (first == null) throw new ContentRepositoryException("file history is unavailable");
        return new History(first, last);
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

    private record History(Instant createdAt, Instant updatedAt) {}
}
