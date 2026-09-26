package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;

/** Disposable public derivatives. Cache keys and hits convey no publication authority. */
public final class PublicThumbnailCache {
    private static final int HEADER_BYTES = 128;
    private static final long CAPACITY = 32L * 1024 * 1024;
    private final BoundedDiskCache files;

    public PublicThumbnailCache(Path root) {
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("thumbnail cache needs an absolute directory");
        }
        this.files = new BoundedDiskCache(
                root,
                ".thumb",
                CAPACITY,
                HEADER_BYTES,
                HEADER_BYTES + AlbumThumbnailRenderer.MAX_BYTES,
                new ChecksumHeader());
    }

    public byte[] get(WorkspaceId workspace, String source, Supplier<byte[]> renderer) {
        String key = BoundedDiskCache.sha256((workspace + "\n" + source + "\n" + AlbumThumbnailRenderer.REPRESENTATION)
                .getBytes(StandardCharsets.UTF_8));
        return files.get(key, PublicThumbnailCache::validate, renderer);
    }

    private static byte[] validate(byte[] image) {
        AlbumThumbnailRenderer.validateEncoded(image);
        return image;
    }

    /** Binds each stored file to its key and to the checksum of the image that follows the header. */
    private static final class ChecksumHeader implements BoundedDiskCache.Framing {
        @Override
        public byte[] encode(String key, byte[] image) {
            byte[] header = (key + BoundedDiskCache.sha256(image)).getBytes(StandardCharsets.US_ASCII);
            byte[] stored = Arrays.copyOf(header, HEADER_BYTES + image.length);
            System.arraycopy(image, 0, stored, HEADER_BYTES, image.length);
            return stored;
        }

        @Override
        public byte[] decode(String key, byte[] stored) {
            byte[] image = Arrays.copyOfRange(stored, HEADER_BYTES, stored.length);
            String header = new String(stored, 0, HEADER_BYTES, StandardCharsets.US_ASCII);
            return header.equals(key + BoundedDiskCache.sha256(image)) ? image : null;
        }
    }
}
