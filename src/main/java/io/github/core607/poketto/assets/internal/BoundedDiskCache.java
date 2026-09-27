package io.github.core607.poketto.assets.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.assets.AssetStorageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One disposable directory of SHA-256-keyed files, bounded in bytes and entries and evicted least
 * recently used first. Each file is written under a pending name and moved into place atomically,
 * and every directory component is checked for containment before use. The lock is never held while
 * a loader runs, so a loader may take an authority lock.
 */
final class BoundedDiskCache {
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_SCANNED_FILES = 10_000;
    private static final Pattern PENDING = Pattern.compile("pending-[0-9a-f-]{36}");
    private final Path root;
    private final String suffix;
    private final Pattern entryName;
    private final long capacity;
    private final int minFileBytes;
    private final int maxFileBytes;
    private final Framing framing;
    private final Map<String, Long> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    private boolean initialized;

    /** Encodes a value into its stored file; decoding returns null for a file that is not a valid entry for its key. */
    interface Framing {
        Framing NONE = new Framing() {
            @Override
            public byte[] encode(String key, byte[] value) {
                return value;
            }

            @Override
            public byte[] decode(String key, byte[] stored) {
                return stored;
            }
        };

        byte[] encode(String key, byte[] value);

        byte[] decode(String key, byte[] stored);
    }

    /** The root must be absolute; files outside {@code minFileBytes..maxFileBytes} are discarded unread. */
    BoundedDiskCache(Path root, String suffix, long capacity, int minFileBytes, int maxFileBytes, Framing framing) {
        this.root = root.normalize();
        this.suffix = suffix;
        this.entryName = Pattern.compile("[0-9a-f]{64}" + Pattern.quote(suffix));
        this.capacity = capacity;
        this.minFileBytes = minFileBytes;
        this.maxFileBytes = maxFileBytes;
        this.framing = framing;
    }

    /**
     * Returns the validated cached value, or loads, validates and stores a new one. A cached value
     * that fails validation is discarded and loaded again; a loaded value that fails is never stored.
     */
    <T> T get(String key, Function<byte[], T> validate, Supplier<byte[]> loader) {
        byte[] cached = cached(key);
        if (cached != null) {
            try {
                return validate.apply(cached);
            } catch (AssetStorageException invalid) {
                discard(key);
            }
        }
        byte[] loaded = loader.get();
        T value = validate.apply(loaded);
        put(key, loaded);
        return value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for cache keys", exception);
        }
    }

    private synchronized byte[] cached(String key) {
        try {
            initialize();
            safeDirectories(true);
            Path file = file(key);
            if (!Files.exists(file, NOFOLLOW_LINKS)) {
                forget(key);
                return null;
            }
            requireRegular(file);
            byte[] stored;
            try (var input = Files.newInputStream(file, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                stored = BoundedImageReads.read(input, maxFileBytes + 1);
            }
            byte[] value = null;
            if (stored.length >= minFileBytes && stored.length <= maxFileBytes) {
                value = framing.decode(key, stored);
            }
            if (value == null) {
                Files.delete(file);
                forget(key);
                return null;
            }
            entries.get(key);
            return value;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private synchronized void put(String key, byte[] value) {
        Path pending = null;
        try {
            initialize();
            safeDirectories(true);
            pending = root.resolve("pending-" + UUID.randomUUID());
            byte[] stored = framing.encode(key, value);
            Files.write(pending, stored, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS);
            Path target = file(key);
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                requireRegular(target);
            }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forget(key);
            entries.put(key, (long) stored.length);
            bytes += stored.length;
            evict();
        } catch (IOException exception) {
            throw unavailable(exception);
        } finally {
            if (pending != null) {
                try {
                    Files.deleteIfExists(pending);
                } catch (IOException ignored) {
                    // Initialization reaps abandoned disposable writes.
                }
            }
        }
    }

    private synchronized void discard(String key) {
        try {
            safeDirectories(false);
            Path file = file(key);
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                requireRegular(file);
            }
            Files.deleteIfExists(file);
            forget(key);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private void initialize() throws IOException {
        if (initialized) {
            return;
        }
        safeDirectories(true);
        try (var files = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path file : files) {
                if (++count > MAX_SCANNED_FILES) {
                    throw new IOException("cache directory entry bound exceeded");
                }
                requireRegular(file);
                String name = file.getFileName().toString();
                if (PENDING.matcher(name).matches()) {
                    Files.delete(file);
                    continue;
                }
                if (!entryName.matcher(name).matches()) {
                    throw new IOException("unexpected cache entry");
                }
                long length = Files.size(file);
                if (length < minFileBytes || length > maxFileBytes) {
                    Files.delete(file);
                    continue;
                }
                String key = name.substring(0, 64);
                forget(key);
                entries.put(key, length);
                bytes += length;
            }
        }
        initialized = true;
        evict();
    }

    private void evict() throws IOException {
        while (bytes > capacity || entries.size() > MAX_ENTRIES) {
            String key = entries.keySet().iterator().next();
            Path file = file(key);
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                requireRegular(file);
                Files.delete(file);
            }
            forget(key);
        }
    }

    private void forget(String key) {
        Long previous = entries.remove(key);
        if (previous != null) {
            bytes -= previous;
        }
    }

    private Path file(String key) {
        return root.resolve(key + suffix);
    }

    private void safeDirectories(boolean create) throws IOException {
        Path cursor = root.getRoot();
        for (Path segment : root) {
            cursor = cursor.resolve(segment);
            if (create && !Files.exists(cursor, NOFOLLOW_LINKS)) {
                Files.createDirectory(cursor);
            }
            StorageDirectories.requireContained(cursor);
        }
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw new IOException("cache entry is not a regular file");
        }
    }

    private static AssetStorageException unavailable(IOException cause) {
        var failure = new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
        failure.initCause(cause);
        return failure;
    }
}
