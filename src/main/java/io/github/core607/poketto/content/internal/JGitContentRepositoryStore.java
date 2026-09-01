package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitContentRepositoryStore implements ContentRepositoryStore {

    private final RepositoryAuthority authority;
    private final CanonicalDocumentCodec codec;

    JGitContentRepositoryStore(RepositoryAuthority authority, CanonicalDocumentCodec codec) {
        this.authority = Objects.requireNonNull(authority, "repository authority must not be null");
        this.codec = Objects.requireNonNull(codec, "document codec must not be null");
    }

    @Override
    public void ensureReady(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        authority.ensureReady(workspaceId);
    }

    @Override
    public List<StoredDocument> scan(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        return authority.read(workspaceId, snapshot -> scan(snapshot, workspaceId));
    }

    List<StoredDocument> scan(RepositoryAuthority.Snapshot snapshot, WorkspaceId workspaceId) {
        try (Repository repository = openCache(snapshot.worktree(), workspaceId)) {
            ObjectId commit = snapshot.commitId()
                    .map(ObjectId::fromString)
                    .orElseGet(ObjectId::zeroId);
            return scan(repository, commit, workspaceId);
        }
    }

    List<StoredDocument> scan(
            Repository repository, ObjectId commit, WorkspaceId workspaceId) {
        if (commit.equals(ObjectId.zeroId())) {
            return List.of();
        }
        try {
            ObjectId tree = repository.resolve(commit.name() + "^{tree}");
            if (tree == null) {
                throw failure(workspaceId, "resolved main does not name a commit tree", null);
            }
            List<TreeEntry> entries = readManagedEntries(repository, tree, workspaceId);
            rejectPathCollisions(entries, workspaceId);

            List<StoredDocument> documents = new ArrayList<>(entries.size());
            for (TreeEntry entry : entries) {
                final DocumentContent content;
                try {
                    content = codec.parse(entry.bytes());
                } catch (IllegalArgumentException exception) {
                    throw failure(
                            workspaceId,
                            "invalid document " + entry.path() + ": " + exception.getMessage(),
                            exception);
                }
                documents.add(new StoredDocument(
                        entry.path(), content, DocumentRevision.sha256(entry.bytes())));
            }
            rejectDuplicateIds(documents, workspaceId);
            return List.copyOf(documents);
        } catch (ContentRepositoryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(workspaceId, "resolved main cannot be scanned", exception);
        }
    }

    static Repository openCache(Path worktree, WorkspaceId workspaceId) {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            builder.findGitDir(worktree.toFile());
            if (builder.getGitDir() == null) {
                throw failure(workspaceId, "materialized cache is not a Git worktree", null);
            }
            return builder.build();
        } catch (IOException exception) {
            throw failure(workspaceId, "materialized cache cannot be opened", exception);
        }
    }

    private static List<TreeEntry> readManagedEntries(
            Repository repository, ObjectId tree, WorkspaceId workspaceId) throws IOException {
        List<TreeEntry> entries = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(tree);
            walk.setRecursive(true);
            while (walk.next()) {
                String path = walk.getPathString();
                if (!path.equals("documents") && !path.startsWith("documents/")) {
                    continue;
                }
                if (!(FileMode.REGULAR_FILE.equals(walk.getFileMode(0))
                        || FileMode.EXECUTABLE_FILE.equals(walk.getFileMode(0)))) {
                    throw new ContentRepositoryException(
                            "workspace " + workspaceId + " has a non-file managed document at "
                                    + path);
                }
                try {
                    DocumentPathRules.validate(path);
                } catch (IllegalArgumentException exception) {
                    throw new ContentRepositoryException(
                            "workspace " + workspaceId + " has an invalid managed path " + path
                                    + ": " + exception.getMessage(),
                            exception);
                }
                ObjectLoader loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB);
                entries.add(new TreeEntry(path, loader.getBytes()));
            }
        }
        entries.sort(Comparator.comparing(TreeEntry::path));
        return entries;
    }

    private static void rejectPathCollisions(
            List<TreeEntry> entries, WorkspaceId workspaceId) {
        Map<String, List<String>> byCollisionKey = entries.stream().collect(Collectors.groupingBy(
                entry -> DocumentPathRules.collisionKey(entry.path()),
                LinkedHashMap::new,
                Collectors.mapping(TreeEntry::path, Collectors.toList())));
        List<String> conflicts = byCollisionKey.values().stream()
                .filter(paths -> paths.size() > 1)
                .flatMap(List::stream)
                .sorted()
                .toList();
        if (!conflicts.isEmpty()) {
            throw failure(
                    workspaceId,
                    "managed document paths collide after Unicode normalization and case folding: "
                            + conflicts,
                    null);
        }
    }

    private static void rejectDuplicateIds(
            List<StoredDocument> documents, WorkspaceId workspaceId) {
        Map<DocumentId, List<String>> byId = documents.stream().collect(Collectors.groupingBy(
                document -> document.content().metadata().id(),
                LinkedHashMap::new,
                Collectors.mapping(StoredDocument::repositoryPath, Collectors.toList())));
        List<String> conflicts = byId.values().stream()
                .filter(paths -> paths.size() > 1)
                .flatMap(List::stream)
                .sorted()
                .toList();
        if (!conflicts.isEmpty()) {
            throw failure(
                    workspaceId,
                    "duplicate document ids appear at repository paths " + conflicts,
                    null);
        }
    }

    private static ContentRepositoryException failure(
            WorkspaceId workspaceId, String detail, Throwable cause) {
        String message = "workspace " + workspaceId + " resolved repository snapshot: " + detail;
        return cause == null
                ? new ContentRepositoryException(message)
                : new ContentRepositoryException(message, cause);
    }

    private record TreeEntry(String path, byte[] bytes) {
    }
}
