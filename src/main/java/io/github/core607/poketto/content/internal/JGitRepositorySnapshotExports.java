package io.github.core607.poketto.content.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.transport.BundleWriter;

final class JGitRepositorySnapshotExports implements RepositorySnapshotExports {
    private final RepositoryAuthority authority;
    private final AuthService auth;
    private final PublicContentSnapshots snapshots;
    private final Path staging;
    private final long maxBytes;
    private final Duration timeout;
    private final Set<UUID> exports = ConcurrentHashMap.newKeySet();
    private boolean initialized;

    private record PublicRevision(WorkspaceId workspace, String commit) {}

    private final java.util.Map<PublicRevision, String> publicFingerprints =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<PublicRevision, String> eldest) {
                    return size() > 64;
                }
            });

    JGitRepositorySnapshotExports(
            RepositoryAuthority authority,
            AuthService auth,
            Path staging,
            long maxBytes,
            Duration timeout,
            PublicContentSnapshots snapshots) {
        if (!staging.isAbsolute()
                || maxBytes < 1024
                || maxBytes > 1024L * 1024 * 1024
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("invalid repository export bounds");
        this.authority = authority;
        this.auth = auth;
        this.snapshots = snapshots;
        this.staging = staging.normalize();
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    @Override
    public PublicExport createPublic(AuthPrincipal actor, WorkspaceId workspace) {
        auth.authorize(actor, workspace, Capability.EXECUTE_REPOSITORY);
        var snapshot = snapshots.withCurrent(workspace, value -> value);
        String authorityCommit = snapshot.commit().orElseThrow(JGitRepositorySnapshotExports::unavailable);
        long deadline = System.nanoTime() + timeout.toNanos();
        var projection = projection(workspace, snapshot, deadline);
        String fingerprint = PublicExecutionProjection.fingerprint(projection);
        publicFingerprints.put(new PublicRevision(workspace, authorityCommit), fingerprint);
        UUID id = UUID.randomUUID();
        Path repositoryPath = staging.resolve(id + ".projection");
        Path pending = staging.resolve(id + ".pending");
        Path published = staging.resolve(id + ".bundle");
        try {
            safeStaging();
            clearAbandoned();
            Files.createDirectory(repositoryPath);
            privatePermissions(repositoryPath, true);
            try (var git = org.eclipse.jgit.api.Git.init()
                            .setBare(true)
                            .setDirectory(repositoryPath.toFile())
                            .call();
                    var inserter = git.getRepository().newObjectInserter()) {
                var index = org.eclipse.jgit.dircache.DirCache.newInCore();
                var builder = index.builder();
                for (var file : projection.files().entrySet().stream()
                        .sorted((a, b) -> java.util.Arrays.compareUnsigned(
                                a.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                b.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .toList()) {
                    checkDeadline(deadline);
                    var entry = new org.eclipse.jgit.dircache.DirCacheEntry(file.getKey());
                    entry.setFileMode(org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue()));
                    builder.add(entry);
                }
                builder.finish();
                var baseline = new org.eclipse.jgit.lib.CommitBuilder();
                baseline.setTreeId(index.writeTree(inserter));
                var author = new org.eclipse.jgit.lib.PersonIdent(
                        "Poketto", "poketto@invalid", java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
                baseline.setAuthor(author);
                baseline.setCommitter(author);
                baseline.setMessage("Public reading projection\n");
                ObjectId commit = inserter.insert(baseline);
                inserter.flush();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                BundleWriter bundle = new BundleWriter(git.getRepository());
                PackConfig pack = new PackConfig(git.getRepository());
                pack.setThreads(1);
                pack.setDeltaCompress(false);
                bundle.setPackConfig(pack);
                bundle.include("refs/heads/snapshot", commit);
                try (OutputStream output = Files.newOutputStream(
                                pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS);
                        var hashed = new DigestOutputStream(output, digest);
                        var bounded = new BoundedOutput(hashed, maxBytes, deadline)) {
                    privatePermissions(pending, false);
                    bundle.writeBundle(new DeadlineMonitor(deadline), bounded);
                }
                try (FileChannel file = FileChannel.open(pending, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                    file.force(true);
                }
                auth.authorize(actor, workspace, Capability.EXECUTE_REPOSITORY);
                snapshots.withCurrent(workspace, current -> {
                    if (!current.commit().equals(snapshot.commit())
                            || !current.articles().equals(snapshot.articles())) throw unavailable();
                    return null;
                });
                long size = Files.size(pending);
                Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE);
                exports.add(id);
                return new PublicExport(
                        workspace,
                        new Export(id, commit.name(), HexFormat.of().formatHex(digest.digest()), size),
                        authorityCommit,
                        fingerprint,
                        projection.sourcePaths());
            }
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(pending);
                Files.deleteIfExists(published);
            } catch (IOException ignored) {
                /* Unacknowledged export. */
            }
            auth.authorize(actor, workspace, Capability.EXECUTE_REPOSITORY);
            throw new ContentRepositoryException("public execution projection could not be exported", exception);
        } finally {
            try {
                removeProjection(repositoryPath);
            } catch (IOException exception) {
                exports.remove(id);
                try {
                    Files.deleteIfExists(pending);
                    Files.deleteIfExists(published);
                } catch (IOException ignored) {
                    /* Disposable export cleanup failed. */
                }
                throw new ContentRepositoryException("public execution projection cleanup failed", exception);
            }
        }
    }

    private PublicExecutionProjection.Projection projection(
            WorkspaceId workspace, PublicContentSnapshot snapshot, long deadline) {
        if (!workspace.equals(snapshot.workspaceId())) throw unavailable();
        String authorityCommit = snapshot.commit().orElseThrow(JGitRepositorySnapshotExports::unavailable);
        return authority.readImmutableObjects(workspace, objects -> {
            var policy = JGitPublicContentSnapshots.policy(objects, authorityCommit);
            if (policy.state() != RepositoryPublishingPolicy.State.ENABLED) throw unavailable();
            RepositoryMediaIndex index = RepositoryMediaIndex.empty();
            try (RevWalk commits = new RevWalk(objects);
                    var entry = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                            objects,
                            RepositoryMediaIndex.PATH,
                            commits.parseCommit(ObjectId.fromString(authorityCommit))
                                    .getTree())) {
                if (entry != null) {
                    if (!org.eclipse.jgit.lib.FileMode.REGULAR_FILE.equals(entry.getFileMode(0))) throw unavailable();
                    var blob = objects.open(entry.getObjectId(0), Constants.OBJ_BLOB);
                    if (blob.getSize() > RepositoryMediaIndex.MAX_BYTES) throw unavailable();
                    index = RepositoryMediaIndex.parse(blob.getBytes(RepositoryMediaIndex.MAX_BYTES));
                }
            }
            checkDeadline(deadline);
            return PublicExecutionProjection.build(
                    snapshot, index, policy, Math.min(maxBytes * 2, ContentLimits.MAX_WORKSPACE_BYTES));
        });
    }

    @Override
    public void requireCurrentPublic(AuthPrincipal actor, WorkspaceId workspace, PublicExport exported) {
        auth.authorize(actor, workspace, Capability.EXECUTE_REPOSITORY);
        if (!workspace.equals(exported.workspaceId())) throw unavailable();
        var current = snapshots.withCurrent(workspace, value -> value);
        String commit = current.commit().orElseThrow(JGitRepositorySnapshotExports::unavailable);
        var revision = new PublicRevision(workspace, commit);
        String fingerprint = publicFingerprints.get(revision);
        if (fingerprint == null) {
            fingerprint = PublicExecutionProjection.fingerprint(
                    projection(workspace, current, System.nanoTime() + timeout.toNanos()));
            publicFingerprints.put(revision, fingerprint);
        }
        if (!fingerprint.equals(exported.projectionSha256())) throw unavailable();
        auth.authorize(actor, workspace, Capability.EXECUTE_REPOSITORY);
        snapshots.withCurrent(workspace, latest -> {
            if (!latest.commit().equals(current.commit())) throw unavailable();
            return null;
        });
    }

    private void removeProjection(Path path) throws IOException {
        if (!path.getParent().equals(staging) || !path.getFileName().toString().matches("[0-9a-f-]{36}\\.projection"))
            throw unavailable();
        if (!Files.exists(path, NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) throw unavailable();
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                // JGit makes loose objects read-only; Windows requires clearing that flag before deletion.
                if (attributes.isRegularFile()) {
                    var dos = Files.getFileAttributeView(
                            file, java.nio.file.attribute.DosFileAttributeView.class, NOFOLLOW_LINKS);
                    if (dos != null) dos.setReadOnly(false);
                }
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public Export create(AuthPrincipal actor, WorkspaceId workspace, Optional<String> requested) {
        return auth.withAuthorization(
                actor,
                workspace,
                Set.of(Capability.READ_PRIVATE, Capability.EXECUTE_REPOSITORY),
                () -> authority.readObjects(workspace, snapshot -> {
                    UUID id = UUID.randomUUID();
                    Path pending = staging.resolve(id + ".pending");
                    try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspace)) {
                        long deadline = System.nanoTime() + timeout.toNanos();
                        String commit = requested.orElseGet(
                                () -> snapshot.commitId().orElseThrow(JGitRepositorySnapshotExports::unavailable));
                        if (!commit.matches("[0-9a-f]{40}")
                                || snapshot.commitId().isEmpty()) throw unavailable();
                        try (RevWalk walk = new RevWalk(repository)) {
                            walk.markStart(walk.parseCommit(
                                    ObjectId.fromString(snapshot.commitId().orElseThrow())));
                            boolean found = false;
                            int count = 0;
                            for (var item : walk) {
                                checkDeadline(deadline);
                                if (++count > 100_000) throw unavailable();
                                if (item.name().equals(commit)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) throw unavailable();
                        }
                        preflight(repository, commit, deadline);
                        safeStaging();
                        clearAbandoned();
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        BundleWriter bundle = new BundleWriter(repository);
                        PackConfig pack = new PackConfig(repository);
                        pack.setThreads(1);
                        pack.setDeltaCompress(false);
                        bundle.setPackConfig(pack);
                        bundle.include("refs/heads/snapshot", ObjectId.fromString(commit));
                        try (OutputStream output = Files.newOutputStream(
                                        pending,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        NOFOLLOW_LINKS);
                                var hashed = new DigestOutputStream(output, digest);
                                var bounded = new BoundedOutput(hashed, maxBytes, deadline)) {
                            privatePermissions(pending, false);
                            bundle.writeBundle(new DeadlineMonitor(deadline), bounded);
                        }
                        try (FileChannel file = FileChannel.open(pending, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                            file.force(true);
                        }
                        long size = Files.size(pending);
                        Files.move(pending, staging.resolve(id + ".bundle"), StandardCopyOption.ATOMIC_MOVE);
                        exports.add(id);
                        return new Export(id, commit, HexFormat.of().formatHex(digest.digest()), size);
                    } catch (Exception exception) {
                        try {
                            Files.deleteIfExists(pending);
                        } catch (IOException ignored) {
                            /* Disposable incomplete export. */
                        }
                        throw new ContentRepositoryException(
                                "repository execution snapshot could not be exported within its bounds", exception);
                    }
                }));
    }

    private void preflight(Repository repository, String commit, long deadline) throws IOException {
        try (ObjectWalk walk = new ObjectWalk(repository)) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(commit)));
            int count = 0;
            while (walk.next() != null) {
                checkDeadline(deadline);
                if (++count > 100_000) throw unavailable();
            }
            long rawBytes = 0;
            org.eclipse.jgit.revwalk.RevObject object;
            while ((object = walk.nextObject()) != null) {
                checkDeadline(deadline);
                if (++count > 250_000) throw unavailable();
                if (object.getType() == Constants.OBJ_BLOB) {
                    rawBytes += repository
                            .getObjectDatabase()
                            .open(object, Constants.OBJ_BLOB)
                            .getSize();
                    if (rawBytes > maxBytes * 2) throw unavailable();
                }
            }
        }
    }

    @Override
    public void release(UUID exportId) {
        if (!exports.contains(exportId)) return;
        try {
            safeStaging();
            Path file = staging.resolve(exportId + ".bundle");
            if (Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw unavailable();
            Files.deleteIfExists(file);
            exports.remove(exportId);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private void safeStaging() throws IOException {
        Path path = staging.getRoot();
        for (Path segment : staging) {
            path = path.resolve(segment);
            if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path);
            if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) throw unavailable();
        }
        privatePermissions(staging, true);
    }

    private synchronized void clearAbandoned() throws IOException {
        if (initialized) return;
        try (var files = Files.newDirectoryStream(staging)) {
            int count = 0;
            for (Path file : files) {
                if (Files.isDirectory(file, NOFOLLOW_LINKS)
                        && file.getFileName().toString().matches("[0-9a-f-]{36}\\.projection")) {
                    if (++count > 1024) throw unavailable();
                    removeProjection(file);
                    continue;
                }
                if (++count > 1024
                        || !Files.isRegularFile(file, NOFOLLOW_LINKS)
                        || !file.getFileName()
                                .toString()
                                .matches(
                                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(pending|bundle)"))
                    throw unavailable();
                Files.delete(file);
            }
        }
        initialized = true;
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS) != null)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw unavailable();
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("repository execution snapshot could not be exported within its bounds");
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private final long maximum;
        private final long deadline;
        private long bytes;

        BoundedOutput(OutputStream output, long maximum, long deadline) {
            super(output);
            this.maximum = maximum;
            this.deadline = deadline;
        }

        @Override
        public void write(int value) throws IOException {
            checkDeadline(deadline);
            if (++bytes > maximum) throw unavailable();
            out.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            checkDeadline(deadline);
            if (length > maximum - bytes) throw unavailable();
            bytes += length;
            out.write(value, offset, length);
        }
    }

    private record DeadlineMonitor(long deadline) implements ProgressMonitor {
        @Override
        public void start(int tasks) {
            checkDeadline(deadline);
        }

        @Override
        public void beginTask(String title, int work) {
            checkDeadline(deadline);
        }

        @Override
        public void update(int completed) {
            checkDeadline(deadline);
        }

        @Override
        public void endTask() {
            checkDeadline(deadline);
        }

        @Override
        public boolean isCancelled() {
            return System.nanoTime() > deadline || Thread.currentThread().isInterrupted();
        }

        @Override
        public void showDuration(boolean enabled) {}
    }
}
