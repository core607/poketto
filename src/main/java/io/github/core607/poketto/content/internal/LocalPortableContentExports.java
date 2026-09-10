package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Disposable protected ZIP storage; quotas and handles are always owned by a workspace. */
final class LocalPortableContentExports implements PortableContentExports, AutoCloseable {
    record Limits(
            long zipBytes,
            long retainedBytes,
            long workspaceBytes,
            int packages,
            Duration lifetime,
            Duration buildTime) {
        Limits {
            if (zipBytes < 1024
                    || zipBytes > 1024L * 1024 * 1024
                    || retainedBytes < zipBytes
                    || workspaceBytes < zipBytes
                    || workspaceBytes > retainedBytes
                    || packages < 1
                    || packages > 64
                    || lifetime.isNegative()
                    || lifetime.isZero()
                    || lifetime.compareTo(Duration.ofMinutes(30)) > 0
                    || buildTime.isNegative()
                    || buildTime.isZero()
                    || buildTime.compareTo(Duration.ofMinutes(10)) > 0)
                throw new IllegalArgumentException("invalid portable export limits");
        }
    }

    private record Owner(
            WorkspaceId workspace, AuthPrincipal.Kind kind, UUID subject, UUID account, Optional<String> client) {
        static Owner of(AuthPrincipal actor, WorkspaceId workspace, Optional<String> client) {
            if (client.isPresent() && !client.orElseThrow().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid client identity");
            return new Owner(
                    Objects.requireNonNull(workspace),
                    Objects.requireNonNull(actor.kind()),
                    Objects.requireNonNull(actor.subjectId()),
                    Objects.requireNonNull(actor.accountId()),
                    client);
        }
    }

    private static final class Retained {
        final Owner owner;
        final Export receipt;
        final Path path;
        final Runnable authorization;
        int readers;
        boolean retired;

        Retained(Owner owner, Export receipt, Path path, Runnable authorization) {
            this.owner = owner;
            this.receipt = receipt;
            this.path = path;
            this.authorization = authorization;
        }
    }

    private final AuthService auth;
    private final PortableContentPlanner planner;
    private final Path root;
    private final Clock clock;
    private final Limits limits;
    private final Semaphore builders = new Semaphore(1);
    private final Semaphore transfers = new Semaphore(2);
    private final Map<UUID, Retained> retained = new HashMap<>();
    private final Map<WorkspaceId, Long> workspaceBytes = new HashMap<>();
    private final Set<WorkspaceId> downloading = new HashSet<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "portable-export-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private long bytes;
    private Owner building;
    private boolean buildCancelled;
    private final Map<Path, WorkspaceId> orphans = new HashMap<>();
    private FileChannel ownership;
    private FileLock lock;

    LocalPortableContentExports(
            AuthService auth, PortableContentPlanner planner, Path root, Clock clock, Limits limits) {
        if (!root.isAbsolute() || !root.normalize().equals(root))
            throw new IllegalArgumentException("export staging must be absolute and normalized");
        this.auth = auth;
        this.planner = planner;
        this.root = root;
        this.clock = clock;
        this.limits = limits;
        cleanup.scheduleWithFixedDelay(this::reapQuietly, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public Export create(
            AuthPrincipal actor,
            WorkspaceId workspace,
            List<String> selections,
            boolean publicOnly,
            Optional<String> client) {
        Owner owner = Owner.of(actor, workspace, client);
        authorize(actor, workspace, publicOnly);
        if (!builders.tryAcquire()) throw failure(ContentExportException.Reason.CAPACITY);
        lifecycle.readLock().lock();
        boolean reservation = false;
        boolean coordinated = false;
        Path pending = null;
        Path ready = null;
        long deadline = System.nanoTime() + limits.buildTime().toNanos();
        try {
            checkOpen();
            synchronized (this) {
                initialize();
                reap();
                if (retained.size() + orphans.size() >= limits.packages()
                        || limits.zipBytes() > limits.retainedBytes() - bytes
                        || limits.zipBytes() > limits.workspaceBytes() - workspaceBytes.getOrDefault(workspace, 0L))
                    throw failure(ContentExportException.Reason.CAPACITY);
                charge(workspace, limits.zipBytes());
                reservation = true;
                building = owner;
                buildCancelled = false;
                coordinated = true;
            }
            var plan = planner.prepare(actor, workspace, selections, publicOnly);
            if (!plan.workspace().equals(workspace)) throw failure(ContentExportException.Reason.UNAVAILABLE);
            Runnable check = () -> {
                checkOpen();
                synchronized (this) {
                    if (buildCancelled) throw failure(ContentExportException.Reason.NOT_FOUND);
                }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                    throw failure(ContentExportException.Reason.UNAVAILABLE);
                plan.authorize().run();
            };
            check.run();
            UUID id = UUID.randomUUID();
            Path directory = root.resolve(workspace.value().toString());
            protectedDirectory(directory);
            pending = directory.resolve(id + ".pending");
            ready = directory.resolve(id + ".zip");
            MessageDigest digest = digest();
            try (FileChannel file = FileChannel.open(
                    pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
                var output = new DigestOutputStream(java.nio.channels.Channels.newOutputStream(file), digest);
                PortableArchiveWriter.write(
                        output,
                        plan.entries(),
                        new PortableArchiveWriter.Limits(
                                10_000, 768L * 1024 * 1024, limits.zipBytes(), limits.buildTime()),
                        check);
                file.force(true);
            }
            check.run();
            long size = Files.size(pending);
            Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
            pending = null;
            Export receipt = new Export(
                    id,
                    publicOnly,
                    size,
                    HexFormat.of().formatHex(digest.digest()),
                    clock.instant().plus(limits.lifetime()));
            synchronized (this) {
                checkOpen();
                if (buildCancelled) throw failure(ContentExportException.Reason.NOT_FOUND);
                retained.put(id, new Retained(owner, receipt, ready, plan.authorize()));
                charge(workspace, size - limits.zipBytes());
                reservation = false;
                ready = null;
            }
            return receipt;
        } catch (IOException | UnsupportedOperationException error) {
            throw failure(ContentExportException.Reason.UNAVAILABLE);
        } finally {
            try {
                try {
                    if (pending != null) Files.deleteIfExists(pending);
                    if (ready != null) Files.deleteIfExists(ready);
                } catch (IOException error) {
                    // Retry removal before releasing this failed build's reserved capacity.
                    synchronized (this) {
                        orphans.put(pending != null ? pending : ready, workspace);
                    }
                    reservation = false;
                }
                synchronized (this) {
                    if (reservation) charge(workspace, -limits.zipBytes());
                    if (coordinated) {
                        building = null;
                        prune(workspace);
                    }
                }
            } finally {
                lifecycle.readLock().unlock();
                builders.release();
            }
        }
    }

    @Override
    public Export describe(AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client) {
        auth.authorize(actor, workspace);
        lifecycle.readLock().lock();
        try {
            Retained item;
            synchronized (this) {
                item = find(actor, workspace, handle, client);
            }
            check(item);
            return item.receipt;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void copyTo(
            AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client, OutputStream output) {
        auth.authorize(actor, workspace);
        lifecycle.readLock().lock();
        Retained item = null;
        boolean admitted = false;
        try {
            synchronized (this) {
                item = find(actor, workspace, handle, client);
                if (downloading.contains(workspace) || !transfers.tryAcquire())
                    throw failure(ContentExportException.Reason.CAPACITY);
                downloading.add(workspace);
                admitted = true;
                item.readers++;
            }
            check(item);
            try (var input = FileChannel.open(item.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (input.size() != item.receipt.bytes()) throw failure(ContentExportException.Reason.UNAVAILABLE);
                MessageDigest hash = digest();
                ByteBuffer buffer = ByteBuffer.allocate(65536);
                while (input.read(buffer) != -1) {
                    check(item);
                    buffer.flip();
                    hash.update(buffer);
                    buffer.clear();
                }
                if (!HexFormat.of().formatHex(hash.digest()).equals(item.receipt.sha256()))
                    throw failure(ContentExportException.Reason.UNAVAILABLE);
                input.position(0);
                while (input.read(buffer) != -1) {
                    check(item);
                    buffer.flip();
                    output.write(buffer.array(), 0, buffer.remaining());
                    buffer.clear();
                }
                check(item);
            }
        } catch (IOException error) {
            throw failure(ContentExportException.Reason.UNAVAILABLE);
        } finally {
            try {
                synchronized (this) {
                    if (admitted) {
                        item.readers--;
                        downloading.remove(workspace);
                        transfers.release();
                        if (item.retired) remove(item);
                    }
                }
            } finally {
                lifecycle.readLock().unlock();
            }
        }
    }

    @Override
    public void release(AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client) {
        auth.authorize(actor, workspace);
        lifecycle.readLock().lock();
        try {
            synchronized (this) {
                var item = find(actor, workspace, handle, client);
                item.retired = true;
                remove(item);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void closeClient(AuthPrincipal actor, WorkspaceId workspace, String client) {
        if (closed.get()) return;
        Owner owner = Owner.of(actor, workspace, Optional.of(client));
        lifecycle.readLock().lock();
        try {
            synchronized (this) {
                if (owner.equals(building)) buildCancelled = true;
                for (var item : List.copyOf(retained.values()))
                    if (item.owner.equals(owner)) {
                        item.retired = true;
                        remove(item);
                    }
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private Retained find(AuthPrincipal actor, WorkspaceId workspace, UUID handle, Optional<String> client) {
        checkOpen();
        Retained item = retained.get(handle);
        if (item == null
                || !item.owner.equals(Owner.of(actor, workspace, client))
                || item.retired
                || !clock.instant().isBefore(item.receipt.expiresAt()))
            throw failure(ContentExportException.Reason.NOT_FOUND);
        return item;
    }

    private void check(Retained item) {
        checkOpen();
        synchronized (this) {
            if (item.retired || !clock.instant().isBefore(item.receipt.expiresAt()))
                throw failure(ContentExportException.Reason.NOT_FOUND);
        }
        item.authorization.run();
    }

    private void authorize(AuthPrincipal actor, WorkspaceId workspace, boolean publicOnly) {
        auth.authorize(actor, workspace, publicOnly ? new Capability[0] : new Capability[] {Capability.READ_PRIVATE});
    }

    private void checkOpen() {
        if (closed.get()) throw failure(ContentExportException.Reason.UNAVAILABLE);
    }

    private void charge(WorkspaceId workspace, long delta) {
        bytes += delta;
        workspaceBytes.merge(workspace, delta, Long::sum);
        if (workspaceBytes.get(workspace) == 0) workspaceBytes.remove(workspace);
    }

    private void remove(Retained item) {
        if (item.readers != 0) return;
        try {
            Files.deleteIfExists(item.path);
            if (retained.remove(item.receipt.handle(), item)) charge(item.owner.workspace(), -item.receipt.bytes());
            prune(item.owner.workspace());
        } catch (IOException error) {
            throw failure(ContentExportException.Reason.UNAVAILABLE);
        }
    }

    private void reap() {
        for (var orphan : List.copyOf(orphans.entrySet())) {
            try {
                Files.deleteIfExists(orphan.getKey());
                orphans.remove(orphan.getKey());
                charge(orphan.getValue(), -limits.zipBytes());
                prune(orphan.getValue());
            } catch (IOException error) {
                throw failure(ContentExportException.Reason.UNAVAILABLE);
            }
        }
        for (var item : List.copyOf(retained.values())) {
            if (!clock.instant().isBefore(item.receipt.expiresAt())) item.retired = true;
            if (item.retired) remove(item);
        }
    }

    private void prune(WorkspaceId workspace) {
        if (workspaceBytes.containsKey(workspace)
                || building != null && building.workspace().equals(workspace)) return;
        try {
            Files.deleteIfExists(root.resolve(workspace.value().toString()));
        } catch (IOException ignored) {
            /* A remaining entry is retained for the protected startup sweep. */
        }
    }

    private void reapQuietly() {
        lifecycle.readLock().lock();
        try {
            synchronized (this) {
                if (!closed.get()) reap();
            }
        } catch (RuntimeException ignored) {
            /* Retain charged storage for a later cleanup attempt. */
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void initialize() throws IOException {
        if (lock != null) return;
        Path ancestor = root.getRoot();
        for (Path segment : root) {
            ancestor = ancestor.resolve(segment);
            if (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(ancestor);
            if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)
                    || !ancestor.toRealPath().equals(ancestor))
                throw failure(ContentExportException.Reason.UNAVAILABLE);
        }
        protectedDirectory(root);
        FileChannel channel = FileChannel.open(
                root.resolve(".owner.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw failure(ContentExportException.Reason.UNAVAILABLE);
            Files.setPosixFilePermissions(root.resolve(".owner.lock"), PosixFilePermissions.fromString("rw-------"));
            int count = 0;
            try (var directories = Files.newDirectoryStream(root)) {
                for (Path directory : directories) {
                    if (directory.getFileName().toString().equals(".owner.lock")) continue;
                    if (++count > 128
                            || !uuid(directory.getFileName().toString())
                            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                        throw failure(ContentExportException.Reason.UNAVAILABLE);
                    try (var files = Files.newDirectoryStream(directory)) {
                        for (Path file : files) {
                            if (++count > 256
                                    || !file.getFileName().toString().matches("[0-9a-f-]{36}\\.(zip|pending)")
                                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                                throw failure(ContentExportException.Reason.UNAVAILABLE);
                            Files.delete(file);
                        }
                    }
                    Files.delete(directory);
                }
            }
            ownership = channel;
            lock = acquired;
        } catch (IOException | RuntimeException error) {
            if (acquired != null) acquired.release();
            channel.close();
            throw error;
        }
    }

    private static void protectedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory)) throw failure(ContentExportException.Reason.UNAVAILABLE);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private static boolean uuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ContentExportException failure(ContentExportException.Reason reason) {
        return new ContentExportException(reason);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cleanup.shutdownNow();
        lifecycle.writeLock().lock();
        try {
            synchronized (this) {
                try {
                    for (var item : List.copyOf(retained.values())) {
                        item.retired = true;
                        remove(item);
                    }
                    reap();
                } finally {
                    try {
                        if (lock != null) lock.release();
                    } finally {
                        if (ownership != null) ownership.close();
                    }
                }
            }
        } catch (IOException error) {
            throw failure(ContentExportException.Reason.UNAVAILABLE);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
