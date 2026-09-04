package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.ContentSnapshot;
import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JGitContentRepositoryStore implements ContentRepositoryStore {

    private static final Logger log = LoggerFactory.getLogger(JGitContentRepositoryStore.class);
    // Lives in the cache's Git directory beside the write-intent journal, so it survives the
    // worktree resets that materialize each fetched commit and disappears with the cache.
    private static final String VALIDATED_MARKER = "poketto-validated-main";
    private static final String UNBORN = "unborn";

    private final RepositoryAuthority authority;
    private final CanonicalDocumentCodec codec;
    private final Clock clock;
    private final Map<WorkspaceId, ContentSnapshot> snapshots = new ConcurrentHashMap<>();

    JGitContentRepositoryStore(RepositoryAuthority authority, CanonicalDocumentCodec codec, Clock clock) {
        this.authority = Objects.requireNonNull(authority, "repository authority must not be null");
        this.codec = Objects.requireNonNull(codec, "document codec must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void ensureReady(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        try {
            refresh(workspaceId);
        } catch (ContentRepositoryException live) {
            final ContentSnapshot recorded;
            try {
                recorded = restoreLastValidated(workspaceId);
            } catch (ContentRepositoryException unavailable) {
                live.addSuppressed(unavailable);
                throw live;
            }
            log.warn(
                    "workspace {} serves its last validated snapshot at {} because current main is unavailable: {}",
                    workspaceId,
                    recorded.commitId().orElse(UNBORN),
                    live.getMessage());
        }
    }

    @Override
    public ContentSnapshot refresh(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        return authority.read(workspaceId, snapshot -> install(workspaceId, snapshot, scan(snapshot, workspaceId)));
    }

    @Override
    public Optional<ContentSnapshot> snapshot(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        return Optional.ofNullable(snapshots.get(workspaceId));
    }

    @Override
    public List<StoredDocument> scan(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        return authority.read(workspaceId, snapshot -> scan(snapshot, workspaceId));
    }

    /**
     * Makes fully validated documents at the snapshot commit the workspace's current snapshot and
     * records the commit in the cache. Callers hold the workspace lock through the authority, so
     * installs are ordered with the reads and writes that produced them.
     */
    ContentSnapshot install(
            WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot, List<StoredDocument> documents) {
        Instant validatedAt = clock.instant();
        ContentSnapshot validated = new ContentSnapshot(workspaceId, snapshot.commitId(), documents, validatedAt);
        recordValidated(snapshot.worktree(), workspaceId, snapshot.commitId(), validatedAt);
        snapshots.put(workspaceId, validated);
        return validated;
    }

    private ContentSnapshot restoreLastValidated(WorkspaceId workspaceId) {
        ContentSnapshot restored = authority.readCache(workspaceId, cache -> {
            ValidatedMarker marker = readValidated(cache.worktree(), workspaceId);
            try (Repository repository = openCache(cache.worktree(), workspaceId)) {
                ObjectId commit = marker.commitId().map(ObjectId::fromString).orElseGet(ObjectId::zeroId);
                if (!commit.equals(ObjectId.zeroId())
                        && !repository.getObjectDatabase().has(commit)) {
                    throw failure(workspaceId, "last validated commit is no longer in the cache", null);
                }
                return new ContentSnapshot(
                        workspaceId, marker.commitId(), scan(repository, commit, workspaceId), marker.validatedAt());
            } catch (IOException exception) {
                throw failure(workspaceId, "last validated commit cannot be checked", exception);
            }
        });
        snapshots.put(workspaceId, restored);
        return restored;
    }

    List<StoredDocument> scan(RepositoryAuthority.Snapshot snapshot, WorkspaceId workspaceId) {
        try (Repository repository = openCache(snapshot.worktree(), workspaceId)) {
            ObjectId commit = snapshot.commitId().map(ObjectId::fromString).orElseGet(ObjectId::zeroId);
            return scan(repository, commit, workspaceId);
        }
    }

    List<StoredDocument> scan(Repository repository, ObjectId commit, WorkspaceId workspaceId) {
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
                            workspaceId, "invalid document " + entry.path() + ": " + exception.getMessage(), exception);
                }
                documents.add(new StoredDocument(entry.path(), content, DocumentRevision.sha256(entry.bytes())));
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

    private static List<TreeEntry> readManagedEntries(Repository repository, ObjectId tree, WorkspaceId workspaceId)
            throws IOException {
        List<TreeEntry> entries = new ArrayList<>();
        long totalBytes = 0;
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
                            "workspace " + workspaceId + " has a non-file managed document at " + path);
                }
                try {
                    DocumentPathRules.validate(path);
                } catch (IllegalArgumentException exception) {
                    throw new ContentRepositoryException(
                            "workspace " + workspaceId + " has an invalid managed path " + path + ": "
                                    + exception.getMessage(),
                            exception);
                }
                if (entries.size() == ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) {
                    throw failure(
                            workspaceId,
                            "managed documents exceed " + ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE + " at " + path,
                            null);
                }
                // The size is known before the blob is loaded, so an oversized document is
                // rejected without ever being read into memory.
                ObjectLoader loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB);
                long size = loader.getSize();
                if (size > ContentLimits.MAX_DOCUMENT_BYTES) {
                    throw failure(
                            workspaceId,
                            "invalid document " + path + ": document must not exceed "
                                    + ContentLimits.MAX_DOCUMENT_BYTES + " bytes: " + size,
                            null);
                }
                totalBytes += size;
                if (totalBytes > ContentLimits.MAX_WORKSPACE_BYTES) {
                    throw failure(
                            workspaceId,
                            "managed documents exceed " + ContentLimits.MAX_WORKSPACE_BYTES + " bytes at " + path,
                            null);
                }
                entries.add(new TreeEntry(path, loader.getBytes()));
            }
        }
        entries.sort(Comparator.comparing(TreeEntry::path));
        return entries;
    }

    private static void rejectPathCollisions(List<TreeEntry> entries, WorkspaceId workspaceId) {
        Map<String, List<String>> byCollisionKey = entries.stream()
                .collect(Collectors.groupingBy(
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
                    "managed document paths collide after Unicode normalization and case folding: " + conflicts,
                    null);
        }
    }

    private static void rejectDuplicateIds(List<StoredDocument> documents, WorkspaceId workspaceId) {
        Map<DocumentId, List<String>> byId = documents.stream()
                .collect(Collectors.groupingBy(
                        document -> document.content().metadata().id(),
                        LinkedHashMap::new,
                        Collectors.mapping(StoredDocument::repositoryPath, Collectors.toList())));
        List<String> conflicts = byId.values().stream()
                .filter(paths -> paths.size() > 1)
                .flatMap(List::stream)
                .sorted()
                .toList();
        if (!conflicts.isEmpty()) {
            throw failure(workspaceId, "duplicate document ids appear at repository paths " + conflicts, null);
        }
    }

    private static void recordValidated(
            Path worktree, WorkspaceId workspaceId, Optional<String> commitId, Instant validatedAt) {
        try (Repository repository = openCache(worktree, workspaceId)) {
            Files.writeString(
                    markerPath(repository),
                    commitId.orElse(UNBORN) + " " + validatedAt + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC);
        } catch (IOException exception) {
            throw failure(workspaceId, "validated commit cannot be recorded in the cache", exception);
        }
    }

    private static ValidatedMarker readValidated(Path worktree, WorkspaceId workspaceId) {
        try (Repository repository = openCache(worktree, workspaceId)) {
            Path marker = markerPath(repository);
            if (Files.notExists(marker)) {
                throw failure(workspaceId, "no validated commit is recorded in the cache", null);
            }
            String[] fields =
                    Files.readString(marker, StandardCharsets.UTF_8).strip().split(" ", -1);
            if (fields.length != 2) {
                throw failure(workspaceId, "the recorded validated commit is unreadable", null);
            }
            Optional<String> commitId = fields[0].equals(UNBORN)
                    ? Optional.empty()
                    : Optional.of(ObjectId.fromString(fields[0]).name());
            return new ValidatedMarker(commitId, Instant.parse(fields[1]));
        } catch (IOException | IllegalArgumentException | DateTimeException exception) {
            throw failure(workspaceId, "the recorded validated commit is unreadable", exception);
        }
    }

    private static Path markerPath(Repository repository) {
        return repository.getDirectory().toPath().resolve(VALIDATED_MARKER);
    }

    private static ContentRepositoryException failure(WorkspaceId workspaceId, String detail, Throwable cause) {
        String message = "workspace " + workspaceId + " resolved repository snapshot: " + detail;
        return cause == null ? new ContentRepositoryException(message) : new ContentRepositoryException(message, cause);
    }

    private record TreeEntry(String path, byte[] bytes) {}

    private record ValidatedMarker(Optional<String> commitId, Instant validatedAt) {}
}
