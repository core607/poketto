package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

final class RemoteRepositoryFixture {

    private final Path remotes;
    private final WorkspacePaths paths;
    private final CanonicalDocumentCodec codec = new CanonicalDocumentCodec();
    private final RepositoryBindingSource bindings;
    private final Map<WorkspaceId, Path> remoteByWorkspace = new ConcurrentHashMap<>();
    private final JGitRemoteRepositoryAuthority authority;
    private final JGitContentRepositoryStore store;

    RemoteRepositoryFixture(Path root) {
        this(root, new JGitRemoteGitTransport());
    }

    RemoteRepositoryFixture(Path root, Clock clock) {
        this(root.resolve("data"), root.resolve("remotes"), new JGitRemoteGitTransport(), 32, clock);
    }

    RemoteRepositoryFixture(Path root, int maxCachedWorkspaces, Clock clock) {
        this(root.resolve("data"), root.resolve("remotes"), new JGitRemoteGitTransport(), maxCachedWorkspaces, clock);
    }

    RemoteRepositoryFixture(Path root, RemoteGitTransport transport, Clock clock) {
        this(root.resolve("data"), root.resolve("remotes"), transport, 32, clock);
    }

    RemoteRepositoryFixture(Path root, int maxCachedWorkspaces) {
        this(root.resolve("data"), root.resolve("remotes"), new JGitRemoteGitTransport(), maxCachedWorkspaces);
    }

    RemoteRepositoryFixture(Path root, RemoteGitTransport transport) {
        this(root.resolve("data"), root.resolve("remotes"), transport, 32);
    }

    RemoteRepositoryFixture(Path dataRoot, Path remoteRoot, RemoteGitTransport transport) {
        this(dataRoot, remoteRoot, transport, 32);
    }

    RemoteRepositoryFixture(Path dataRoot, Path remoteRoot, RemoteGitTransport transport, int maxCachedWorkspaces) {
        this(dataRoot, remoteRoot, transport, maxCachedWorkspaces, Clock.systemUTC());
    }

    RemoteRepositoryFixture(
            Path dataRoot, Path remoteRoot, RemoteGitTransport transport, int maxCachedWorkspaces, Clock clock) {
        remotes = remoteRoot.toAbsolutePath();
        paths = new WorkspacePaths(dataRoot.toAbsolutePath());
        bindings = workspaceId -> {
            try {
                Path remote = provision(workspaceId);
                return new RepositoryBinding(
                        new URIish(remote.toUri().toString()), new UsernamePasswordCredentialsProvider("test", "test"));
            } catch (Exception exception) {
                throw new IllegalStateException("test remote cannot be bound", exception);
            }
        };
        authority = new JGitRemoteRepositoryAuthority(paths, bindings, transport, maxCachedWorkspaces, clock);
        store = new JGitContentRepositoryStore(authority, codec, Clock.systemUTC());
    }

    WorkspacePaths paths() {
        return paths;
    }

    CanonicalDocumentCodec codec() {
        return codec;
    }

    RepositoryAuthority authority() {
        return authority;
    }

    ContentRepositoryStore store() {
        return store;
    }

    DocumentWriteService writes(Clock clock) {
        return new JGitDocumentWriteService(authority, codec, store, clock);
    }

    Path cache(WorkspaceId workspaceId) {
        return paths.contentDirectory(workspaceId);
    }

    Path provision(WorkspaceId workspaceId) throws Exception {
        Path existing = remoteByWorkspace.get(workspaceId);
        if (existing != null) {
            return existing;
        }
        Files.createDirectories(remotes);
        Path remote = remotes.resolve(workspaceId + ".git");
        if (Files.isDirectory(remote)) {
            remoteByWorkspace.put(workspaceId, remote);
            return remote;
        }
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            // The fixture supports the same pre-provisioned empty main as production.
        }
        remoteByWorkspace.put(workspaceId, remote);
        return remote;
    }

    Repository openRemote(WorkspaceId workspaceId) throws Exception {
        return new FileRepositoryBuilder()
                .setGitDir(provision(workspaceId).toFile())
                .setBare()
                .build();
    }

    ObjectId remoteHead(WorkspaceId workspaceId) throws Exception {
        try (Repository repository = openRemote(workspaceId)) {
            ObjectId head = repository.resolve(Constants.R_HEADS + "main");
            return head == null ? ObjectId.zeroId() : head;
        }
    }

    ObjectId commitRemote(WorkspaceId workspaceId, Map<String, byte[]> entries) throws Exception {
        return commitRemote(workspaceId, entries, Map.of());
    }

    ObjectId commitRemote(WorkspaceId workspaceId, Map<String, byte[]> entries, Map<String, FileMode> modes)
            throws Exception {
        return commitRemote(workspaceId, entries, modes, Instant.parse("2026-09-01T09:00:00Z"));
    }

    ObjectId commitRemote(WorkspaceId workspaceId, Map<String, byte[]> entries, Map<String, FileMode> modes, Instant at)
            throws Exception {
        try (Repository repository = openRemote(workspaceId);
                ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder tree = cache.builder();
            Map<String, byte[]> sorted = new LinkedHashMap<>();
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
                DirCacheEntry treeEntry = new DirCacheEntry(entry.getKey());
                treeEntry.setFileMode(modes.getOrDefault(entry.getKey(), FileMode.REGULAR_FILE));
                treeEntry.setObjectId(inserter.insert(Constants.OBJ_BLOB, entry.getValue()));
                tree.add(treeEntry);
            }
            tree.finish();

            ObjectId treeId = cache.writeTree(inserter);
            ObjectId before = repository.resolve(Constants.R_HEADS + "main");
            PersonIdent identity = new PersonIdent("Repository Owner", "owner@invalid", at, ZoneOffset.UTC);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            if (before != null) {
                commit.setParentId(before);
            }
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("owner update");
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
            update.setExpectedOldObjectId(before == null ? ObjectId.zeroId() : before);
            update.setNewObjectId(commitId);
            RefUpdate.Result result = update.update();
            assertThat(result).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED);
            return commitId;
        }
    }
}
