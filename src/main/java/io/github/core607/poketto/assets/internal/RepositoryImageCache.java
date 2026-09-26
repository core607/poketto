package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImagePreviewPolicy;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;

/** Disposable workspace-keyed bytes. Its lock is never held while the loader takes an authority lock. */
public final class RepositoryImageCache {
    private final BoundedDiskCache files;

    public RepositoryImageCache(Path root, long capacity) {
        if (!root.isAbsolute() || capacity < RepositoryBlobReader.MAX_BLOB_BYTES || capacity > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("image cache needs an absolute directory and a 16 MiB to 1 GiB bound");
        }
        this.files = new BoundedDiskCache(
                root, ".image", capacity, 0, RepositoryBlobReader.MAX_BLOB_BYTES, BoundedDiskCache.Framing.NONE);
    }

    public Image get(RepositoryBlob blob, Supplier<byte[]> loader) {
        String key = BoundedDiskCache.sha256(
                (blob.workspaceId() + "\n" + blob.objectId()).getBytes(StandardCharsets.US_ASCII));
        return files.get(key, bytes -> validate(blob, bytes), loader);
    }

    private static Image validate(RepositoryBlob blob, byte[] bytes) {
        if (bytes.length != blob.size() || bytes.length > RepositoryBlobReader.MAX_BLOB_BYTES) {
            throw unavailable();
        }
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            if (!formatter.idFor(Constants.OBJ_BLOB, bytes).name().equals(blob.objectId())) {
                throw unavailable();
            }
        }
        return new Image(ImagePreviewPolicy.validate(bytes), bytes);
    }

    private static AssetStorageException unavailable() {
        return new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
    }

    public record Image(String mediaType, byte[] bytes) {}
}
