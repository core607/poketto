package io.github.core607.poketto.assets.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;

/** Disposable workspace-keyed bytes. Its lock is never held while the loader takes an authority lock. */
public final class RepositoryImageCache {
    private final Path root;
    private final long capacity;
    private final Map<String, Long> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    private boolean initialized;

    public RepositoryImageCache(Path root, long capacity) {
        if (!root.isAbsolute() || capacity < RepositoryBlobReader.MAX_BLOB_BYTES || capacity > 1024L * 1024 * 1024)
            throw new IllegalArgumentException("image cache needs an absolute directory and a 16 MiB to 1 GiB bound");
        this.root = root.normalize();
        this.capacity = capacity;
    }

    public Image get(RepositoryBlob blob, Supplier<byte[]> loader) {
        String key = key(blob);
        byte[] cached = cached(key);
        if (cached != null) {
            try {
                return validate(blob, cached);
            } catch (AssetStorageException invalid) {
                discard(key);
            }
        }
        byte[] loaded = loader.get();
        Image image = validate(blob, loaded);
        put(key, loaded);
        return image;
    }

    private synchronized byte[] cached(String key) {
        try {
            initialize();
            safeDirectories(root, true);
            Path file = root.resolve(key + ".image");
            if (!Files.exists(file, NOFOLLOW_LINKS)) {
                Long old = entries.remove(key);
                if (old != null) bytes -= old;
                return null;
            }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw unavailable();
            try (var input = Files.newInputStream(file, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
                byte[] value = BoundedImageReads.read(input, RepositoryBlobReader.MAX_BLOB_BYTES + 1);
                if (value.length > RepositoryBlobReader.MAX_BLOB_BYTES) return null;
                entries.get(key);
                return value;
            }
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private synchronized void put(String key, byte[] value) {
        Path staged = null;
        try {
            initialize();
            safeDirectories(root, true);
            staged = root.resolve("pending-" + UUID.randomUUID());
            Files.write(staged, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS);
            Path target = root.resolve(key + ".image");
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS))
                throw unavailable();
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Long previous = entries.put(key, (long) value.length);
            bytes += value.length - (previous == null ? 0 : previous);
            evict();
        } catch (IOException exception) {
            throw unavailable();
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    /* Disposable pending file. */
                }
            }
        }
    }

    private synchronized void discard(String key) {
        try {
            safeDirectories(root, false);
            Path target = root.resolve(key + ".image");
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS))
                throw unavailable();
            Files.deleteIfExists(target);
            Long old = entries.remove(key);
            if (old != null) bytes -= old;
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private void initialize() throws IOException {
        if (initialized) return;
        safeDirectories(root, true);
        try (var files = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path file : files) {
                if (++count > 10_000 || !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw unavailable();
                String name = file.getFileName().toString();
                if (name.matches("pending-[0-9a-f-]{36}")) {
                    Files.delete(file);
                    continue;
                }
                if (!name.matches("[0-9a-f]{64}\\.image")) throw unavailable();
                long length = Files.size(file);
                if (length > RepositoryBlobReader.MAX_BLOB_BYTES) {
                    Files.delete(file);
                    continue;
                }
                entries.put(name.substring(0, 64), length);
                bytes += length;
            }
        }
        initialized = true;
        evict();
    }

    private void evict() throws IOException {
        while (bytes > capacity || entries.size() > 1024) {
            String first = entries.keySet().iterator().next();
            Path file = root.resolve(first + ".image");
            if (Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw unavailable();
            Files.deleteIfExists(file);
            bytes -= entries.remove(first);
        }
    }

    private static Image validate(RepositoryBlob blob, byte[] bytes) {
        if (bytes.length != blob.size() || bytes.length > RepositoryBlobReader.MAX_BLOB_BYTES) throw unavailable();
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            if (!formatter.idFor(Constants.OBJ_BLOB, bytes).name().equals(blob.objectId())) throw unavailable();
        }
        return new Image(ImagePolicy.validate(bytes), bytes);
    }

    private static String key(RepositoryBlob blob) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((blob.workspaceId() + "\n" + blob.objectId()).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void safeDirectories(Path path, boolean create) throws IOException {
        Path cursor = path.getRoot();
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            if (create && !Files.exists(cursor, NOFOLLOW_LINKS)) Files.createDirectory(cursor);
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || !cursor.toRealPath().equals(cursor)) throw unavailable();
        }
    }

    private static AssetStorageException unavailable() {
        return new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
    }

    public record Image(String mediaType, byte[] bytes) {}
}
