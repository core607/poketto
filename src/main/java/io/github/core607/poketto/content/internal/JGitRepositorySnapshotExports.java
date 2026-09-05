package io.github.core607.poketto.content.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
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
    private final Path staging;
    private final long maxBytes;
    private final Duration timeout;
    private final Set<UUID> exports = ConcurrentHashMap.newKeySet();
    private boolean initialized;

    JGitRepositorySnapshotExports(
            RepositoryAuthority authority, AuthService auth, Path staging, long maxBytes, Duration timeout) {
        if (!staging.isAbsolute()
                || maxBytes < 1024
                || maxBytes > 1024L * 1024 * 1024
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("invalid repository export bounds");
        this.authority = authority;
        this.auth = auth;
        this.staging = staging.normalize();
        this.maxBytes = maxBytes;
        this.timeout = timeout;
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
