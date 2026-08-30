package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitContentRepositoryStore implements ContentRepositoryStore {

    private static final String MAIN_BRANCH = "main";
    private static final String MAIN = Constants.R_HEADS + MAIN_BRANCH;

    private final WorkspacePaths paths;
    private final CanonicalDocumentCodec codec;

    JGitContentRepositoryStore(WorkspacePaths paths, CanonicalDocumentCodec codec) {
        this.paths = Objects.requireNonNull(paths, "workspace paths must not be null");
        this.codec = Objects.requireNonNull(codec, "document codec must not be null");
    }

    @Override
    public synchronized void ensureReady(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        Path contentDirectory = paths.contentDirectory(workspaceId);
        try {
            if (Files.notExists(contentDirectory)) {
                initialize(contentDirectory, workspaceId);
                return;
            }
            if (!Files.isDirectory(contentDirectory)) {
                throw failure(
                        workspaceId,
                        contentDirectory,
                        "content path exists but is not a directory",
                        null);
            }
            if (isEmptyDirectory(contentDirectory)) {
                initialize(contentDirectory, workspaceId);
                return;
            }
            try (Repository repository = openExisting(contentDirectory, workspaceId)) {
                validate(repository, contentDirectory, workspaceId);
            }
        } catch (ContentRepositoryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "repository metadata cannot be read",
                    exception);
        }
    }

    @Override
    public List<StoredDocument> scan(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        ensureReady(workspaceId);
        Path contentDirectory = paths.contentDirectory(workspaceId);
        try (Repository repository = openExisting(contentDirectory, workspaceId)) {
            ObjectId head = repository.resolve(MAIN);
            if (head == null) {
                return List.of();
            }

            ObjectId tree = repository.resolve(MAIN + "^{tree}");
            if (tree == null) {
                throw failure(
                        workspaceId,
                        contentDirectory,
                        "main does not resolve to a commit tree",
                        null);
            }

            List<TreeEntry> entries = readManagedEntries(repository, tree, workspaceId);
            rejectPathCollisions(entries, workspaceId, contentDirectory);

            List<StoredDocument> documents = new ArrayList<>(entries.size());
            for (TreeEntry entry : entries) {
                final DocumentContent content;
                try {
                    content = codec.parse(entry.bytes());
                } catch (IllegalArgumentException exception) {
                    throw failure(
                            workspaceId,
                            contentDirectory,
                            "invalid document " + entry.path() + ": " + exception.getMessage(),
                            exception);
                }
                documents.add(new StoredDocument(
                        entry.path(), content, DocumentRevision.sha256(entry.bytes())));
            }
            rejectDuplicateIds(documents, workspaceId, contentDirectory);
            return List.copyOf(documents);
        } catch (ContentRepositoryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(workspaceId, contentDirectory, "repository cannot be scanned", exception);
        }
    }

    private static void initialize(Path contentDirectory, WorkspaceId workspaceId) {
        try {
            Files.createDirectories(contentDirectory);
            try (Git ignored = Git.init()
                    .setDirectory(contentDirectory.toFile())
                    .setInitialBranch(MAIN_BRANCH)
                    .call()) {
                // The first document write creates the root commit.
            }
        } catch (IOException | GitAPIException exception) {
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "empty content directory cannot be initialized as an unborn main repository",
                    exception);
        }
    }

    static Repository openExisting(Path contentDirectory, WorkspaceId workspaceId) {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            builder.findGitDir(contentDirectory.toFile());
            if (builder.getGitDir() == null) {
                if (Files.exists(contentDirectory.resolve(Constants.DOT_GIT))) {
                    throw failure(
                            workspaceId,
                            contentDirectory,
                            "repository metadata cannot be read",
                            null);
                }
                throw failure(
                        workspaceId,
                        contentDirectory,
                        "non-empty content directory is not a git repository; choose an empty "
                                + "directory or initialize and commit its content explicitly",
                        null);
            }
            return builder.build();
        } catch (RepositoryNotFoundException exception) {
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "non-empty content directory is not a valid git repository; choose an empty "
                            + "directory or repair it explicitly",
                    exception);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ContentRepositoryException repositoryException) {
                throw repositoryException;
            }
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "repository metadata cannot be read",
                    exception);
        }
    }

    private static void validate(
            Repository repository, Path contentDirectory, WorkspaceId workspaceId) {
        if (repository.isBare()) {
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "bare repositories are not accepted; provide a non-bare main worktree",
                    null);
        }

        Path workTree = repository.getWorkTree().toPath().toAbsolutePath().normalize();
        Path expected = contentDirectory.toAbsolutePath().normalize();
        if (!workTree.equals(expected)) {
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "non-empty content directory is not itself a git worktree; initialize and "
                            + "commit it explicitly",
                    null);
        }

        try {
            String currentBranch = repository.getFullBranch();
            if (!MAIN.equals(currentBranch)) {
                throw failure(
                        workspaceId,
                        contentDirectory,
                        "content repository must have main checked out; current HEAD is "
                                + currentBranch,
                        null);
            }
            RepositoryState state = repository.getRepositoryState();
            if (state != RepositoryState.SAFE) {
                throw failure(
                        workspaceId,
                        contentDirectory,
                        "content repository must not have a git operation in progress; state is "
                                + state,
                        null);
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ContentRepositoryException repositoryException) {
                throw repositoryException;
            }
            throw failure(
                    workspaceId,
                    contentDirectory,
                    "repository metadata cannot be read",
                    exception);
        }
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.findAny().isEmpty();
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
            List<TreeEntry> entries, WorkspaceId workspaceId, Path contentDirectory) {
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
                    contentDirectory,
                    "managed document paths collide after Unicode normalization and case folding: "
                            + conflicts,
                    null);
        }
    }

    private static void rejectDuplicateIds(
            List<StoredDocument> documents, WorkspaceId workspaceId, Path contentDirectory) {
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
                    contentDirectory,
                    "duplicate document ids appear at repository paths " + conflicts,
                    null);
        }
    }

    private static ContentRepositoryException failure(
            WorkspaceId workspaceId, Path path, String detail, Throwable cause) {
        String message = "workspace " + workspaceId + " content repository at " + path + ": "
                + detail;
        return cause == null
                ? new ContentRepositoryException(message)
                : new ContentRepositoryException(message, cause);
    }

    private record TreeEntry(String path, byte[] bytes) {
    }
}
