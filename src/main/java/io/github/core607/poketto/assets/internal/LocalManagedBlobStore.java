package io.github.core607.poketto.assets.internal;

import static io.github.core607.poketto.assets.AssetStorageException.Reason.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.ManagedImage;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local immutable storage with an fsynced operation ledger. Cooperating processes serialize a
 * workspace through an OS file lock. The application identity exclusively owns every ancestor;
 * these containment checks do not defend against a hostile privileged process replacing mounts.
 * Unsupported atomic rename or directory fsync fails closed, including Windows directory providers.
 */
public final class LocalManagedBlobStore implements ManagedBlobStore {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new ReentrantLock();
    }

    private final Path root;

    public LocalManagedBlobStore(Path root) {
        Objects.requireNonNull(root, "managed storage root is required");
        if (!root.isAbsolute()) throw new IllegalArgumentException("managed storage root must be absolute");
        this.root = root.normalize();
        try {
            directory(this.root);
            syncDirectory(this.root);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    @Override
    public ManagedAsset upload(WorkspaceId workspace, String operationKey, InputStream original) {
        Objects.requireNonNull(workspace, "workspace is required");
        Objects.requireNonNull(original, "original image stream is required");
        if (operationKey == null || !operationKey.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException(
                    "upload operation key must contain 16 to 128 ASCII letters, digits, hyphens or underscores");
        }
        Path space = root.resolve(workspace.toString());
        ReentrantLock mutex = LOCKS[Math.floorMod(space.hashCode(), LOCKS.length)];
        mutex.lock();
        Path temporary = null;
        try {
            directory(space);
            try (FileChannel lockChannel = FileChannel.open(space.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS);
                    var fileLock = lockChannel.lock()) {
                Path objects = directory(space.resolve("objects"));
                Path operations = directory(space.resolve("operations"));
                Path pending = directory(space.resolve("pending"));
                temporary = directory(pending.resolve(UUID.randomUUID().toString()));
                Path blob = temporary.resolve("bytes");
                MessageDigest digest = sha256();
                long size = 0;
                try (FileChannel out = FileChannel.open(blob, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[8192];
                    while (size <= MAX_UPLOAD_BYTES) {
                        int count =
                                original.read(buffer, 0, (int) Math.min(buffer.length, MAX_UPLOAD_BYTES + 1L - size));
                        if (count < 0) break;
                        if (count == 0) {
                            int single = original.read();
                            if (single < 0) break;
                            buffer[0] = (byte) single;
                            count = 1;
                        }
                        size += count;
                        if (size > MAX_UPLOAD_BYTES) throw new AssetStorageException(TOO_LARGE);
                        digest.update(buffer, 0, count);
                        ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                        while (bytes.hasRemaining()) out.write(bytes);
                    }
                    out.force(true);
                }
                String revision = HexFormat.of().formatHex(digest.digest());
                Path operation = operations.resolve(hash(operationKey.getBytes(StandardCharsets.US_ASCII)));
                if (Files.exists(operation, NOFOLLOW_LINKS)) {
                    ManagedAsset existing = metadata(operation);
                    if (!existing.reference().revision().equals(revision)) {
                        throw new AssetStorageException(IDEMPOTENCY_CONFLICT);
                    }
                    if (!read(workspace, existing.reference()).asset().equals(existing)) throw unavailable();
                    syncDirectory(operations);
                    return existing;
                }
                String mediaType = ImagePolicy.validate(readBounded(blob, MAX_UPLOAD_BYTES));
                ManagedAsset asset =
                        new ManagedAsset(new ManagedAssetReference(UUID.randomUUID(), revision), mediaType, size);
                byte[] manifest = encode(asset);
                writeNew(temporary.resolve("metadata"), manifest);
                syncDirectory(temporary);
                Path published = objects.resolve(asset.reference().assetId().toString());
                if (Files.exists(published, NOFOLLOW_LINKS)) throw unavailable();
                Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
                syncDirectory(objects);
                syncDirectory(pending);
                Path ledgerTemp = operations.resolve("pending-" + UUID.randomUUID());
                try {
                    writeNew(ledgerTemp, manifest);
                    Files.move(ledgerTemp, operation, StandardCopyOption.ATOMIC_MOVE);
                    syncDirectory(operations);
                } finally {
                    Files.deleteIfExists(ledgerTemp);
                }
                return asset;
            }
        } catch (IOException exception) {
            throw unavailable();
        } finally {
            try {
                if (temporary != null) {
                    checkDirectory(temporary);
                    Files.deleteIfExists(temporary.resolve("bytes"));
                    Files.deleteIfExists(temporary.resolve("metadata"));
                    Files.delete(temporary);
                }
            } catch (IOException exception) {
                // A cleanup failure cannot turn an unacknowledged upload into success.
                throw unavailable();
            } finally {
                mutex.unlock();
            }
        }
    }

    @Override
    public ManagedImage read(WorkspaceId workspace, ManagedAssetReference reference) {
        Objects.requireNonNull(workspace, "workspace is required");
        Objects.requireNonNull(reference, "asset reference is required");
        Path object = root.resolve(workspace.toString())
                .resolve("objects")
                .resolve(reference.assetId().toString());
        try {
            if (!Files.exists(object, NOFOLLOW_LINKS)) throw new AssetStorageException(NOT_FOUND);
            checkDirectory(object);
            ManagedAsset asset = metadata(object.resolve("metadata"));
            if (!asset.reference().equals(reference)) throw new AssetStorageException(NOT_FOUND);
            byte[] bytes = readBounded(object.resolve("bytes"), MAX_UPLOAD_BYTES);
            if (bytes.length != asset.size()
                    || !hash(bytes).equals(reference.revision())
                    || !ImagePolicy.validate(bytes).equals(asset.mediaType())) throw unavailable();
            return new ManagedImage(asset, bytes);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private static ManagedAsset metadata(Path path) throws IOException {
        try {
            String[] fields = new String(readBounded(path, 512), StandardCharsets.US_ASCII).split("\n", -1);
            if (fields.length != 5 || !fields[4].isEmpty()) throw unavailable();
            UUID id = UUID.fromString(fields[0]);
            if (!id.toString().equals(fields[0])) throw unavailable();
            return new ManagedAsset(new ManagedAssetReference(id, fields[1]), fields[2], Long.parseLong(fields[3]));
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private static byte[] encode(ManagedAsset asset) {
        return (asset.reference().assetId() + "\n" + asset.reference().revision() + "\n" + asset.mediaType() + "\n"
                        + asset.size() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        checkDirectory(path.getParent());
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw unavailable();
        try (InputStream input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw unavailable();
            return bytes;
        }
    }

    private static Path directory(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            try {
                Files.createDirectory(cursor);
                syncDirectory(cursor.getParent());
            } catch (FileAlreadyExistsException ignored) {
                // Another cooperating store may create the same workspace root.
            }
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || !cursor.toRealPath().equals(cursor)) {
                throw unavailable();
            }
        }
        return path;
    }

    private static void checkDirectory(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path segment : path) {
            cursor = cursor.resolve(segment);
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isDirectory()
                    || attributes.isSymbolicLink()
                    || !cursor.toRealPath().equals(cursor)) throw unavailable();
        }
    }

    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel directory = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
            directory.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static AssetStorageException unavailable() {
        return new AssetStorageException(UNAVAILABLE);
    }
}
