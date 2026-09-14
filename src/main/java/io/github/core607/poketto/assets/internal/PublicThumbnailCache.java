package io.github.core607.poketto.assets.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Disposable public derivatives. Cache keys and hits convey no publication authority. */
public final class PublicThumbnailCache {
    private static final int HEADER_BYTES = 128;
    private static final int MAX_FILE_BYTES = HEADER_BYTES + AlbumThumbnailRenderer.MAX_BYTES;
    private static final int MAX_ENTRIES = 1024;
    private static final long CAPACITY = 32L * 1024 * 1024;
    private final Path root;
    private final Map<String, Long> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    private boolean initialized;

    public PublicThumbnailCache(Path root) {
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("thumbnail cache needs an absolute directory");
        }
        this.root = root.normalize();
    }

    public byte[] get(WorkspaceId workspace, String source, Supplier<byte[]> renderer) {
        String key = digest((workspace + "\n" + source + "\n" + AlbumThumbnailRenderer.REPRESENTATION)
                .getBytes(StandardCharsets.UTF_8));
        byte[] cached = cached(key);
        if (cached != null) {
            return cached;
        }
        byte[] image = renderer.get();
        AlbumThumbnailRenderer.validateEncoded(image);
        put(key, image);
        return image;
    }

    private synchronized byte[] cached(String key) {
        try {
            initialize();
            safeDirectories();
            Path file = file(key);
            if (!Files.exists(file, NOFOLLOW_LINKS)) {
                forget(key);
                return null;
            }
            requireRegular(file);
            try (var input = Files.newInputStream(file, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                byte[] value = BoundedImageReads.read(input, MAX_FILE_BYTES + 1);
                if (value.length >= HEADER_BYTES && value.length <= MAX_FILE_BYTES) {
                    byte[] image = Arrays.copyOfRange(value, HEADER_BYTES, value.length);
                    String header = new String(value, 0, HEADER_BYTES, StandardCharsets.US_ASCII);
                    if (header.equals(key + digest(image))) {
                        try {
                            AlbumThumbnailRenderer.validateEncoded(image);
                            entries.get(key);
                            return image;
                        } catch (AssetStorageException invalid) {
                            // A derived file with invalid image bytes is discarded and regenerated.
                        }
                    }
                }
            }
            Files.delete(file);
            forget(key);
            return null;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private synchronized void put(String key, byte[] image) {
        Path pending = null;
        try {
            initialize();
            safeDirectories();
            pending = root.resolve("pending-" + UUID.randomUUID());
            try (var output = Files.newOutputStream(
                    pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                output.write((key + digest(image)).getBytes(StandardCharsets.US_ASCII));
                output.write(image);
            }
            Path target = file(key);
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                requireRegular(target);
            }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forget(key);
            long length = HEADER_BYTES + image.length;
            entries.put(key, length);
            bytes += length;
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

    private void initialize() throws IOException {
        if (initialized) {
            return;
        }
        safeDirectories();
        try (var files = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path file : files) {
                if (++count > 10_000) {
                    throw new IOException("thumbnail cache directory entry bound exceeded");
                }
                requireRegular(file);
                String name = file.getFileName().toString();
                if (name.matches("pending-[0-9a-f-]{36}")) {
                    Files.delete(file);
                    continue;
                }
                if (!name.matches("[0-9a-f]{64}\\.thumb")) {
                    throw new IOException("unexpected thumbnail cache entry");
                }
                long length = Files.size(file);
                if (length < HEADER_BYTES || length > MAX_FILE_BYTES) {
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
        while (bytes > CAPACITY || entries.size() > MAX_ENTRIES) {
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
        return root.resolve(key + ".thumb");
    }

    private void safeDirectories() throws IOException {
        Path cursor = root.getRoot();
        for (Path segment : root) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, NOFOLLOW_LINKS)) {
                Files.createDirectory(cursor);
            }
            StorageDirectories.requireContained(cursor);
        }
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw new IOException("thumbnail cache entry is not a regular file");
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for thumbnail cache keys", exception);
        }
    }

    private static AssetStorageException unavailable(IOException cause) {
        var failure = new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
        failure.initCause(cause);
        return failure;
    }
}
