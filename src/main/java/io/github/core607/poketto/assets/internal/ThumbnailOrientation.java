package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Node;

/** Orientation transforms run only on the already subsampled raster. */
final class ThumbnailOrientation {
    private ThumbnailOrientation() {}

    static int read(ImageReader reader) throws IOException {
        IIOMetadata metadata = reader.getImageMetadata(0);
        if (metadata == null || !metadata.isStandardMetadataFormatSupported()) {
            return 1;
        }
        Node dimension = child(metadata.getAsTree("javax_imageio_1.0"), "Dimension");
        Node orientation = child(dimension, "ImageOrientation");
        if (orientation == null) {
            return 1;
        }
        Node value = orientation.getAttributes().getNamedItem("value");
        if (value == null) {
            throw invalid();
        }
        return switch (value.getNodeValue()) {
            case "Normal" -> 1;
            case "FlipH" -> 2;
            case "Rotate180" -> 3;
            case "FlipV" -> 4;
            case "FlipVRotate90" -> 5;
            case "Rotate270" -> 6;
            case "FlipHRotate90" -> 7;
            case "Rotate90" -> 8;
            default -> throw invalid();
        };
    }

    private static Node child(Node parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equals(name)) {
                return node;
            }
        }
        return null;
    }

    static BufferedImage apply(BufferedImage source, int orientation) {
        if (orientation == 1) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean transpose = orientation >= 5;
        var result = new BufferedImage(
                transpose ? height : width,
                transpose ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        AffineTransform transform =
                switch (orientation) {
                    case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
                    case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
                    case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
                    case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                    case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
                    case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
                    case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
                    default -> throw new IllegalArgumentException("image orientation must be between one and eight");
                };
        var graphics = result.createGraphics();
        try {
            graphics.drawImage(source, transform, null);
            return result;
        } finally {
            graphics.dispose();
            source.flush();
        }
    }

    /** Uninterpreted EXIF payloads require original delivery to preserve their authored orientation. */
    static void requireSupportedMetadata(byte[] image, String mediaType) {
        boolean png = mediaType.equals("image/png");
        if (!png && !mediaType.equals("image/webp")) {
            return;
        }
        int cursor = png ? 8 : 12;
        int overhead = png ? 12 : 8;
        while (cursor <= image.length - overhead) {
            String type = new String(image, cursor + (png ? 4 : 0), 4, StandardCharsets.US_ASCII);
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(image, cursor + (png ? 0 : 4), 4)
                    .order(png ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                    .getInt());
            if (length > image.length - cursor - overhead) {
                throw invalid();
            }
            if (type.equals(png ? "eXIf" : "EXIF")) {
                throw invalid();
            }
            long advance = overhead + length + (png ? 0 : length % 2);
            if (advance > image.length - cursor) {
                throw invalid();
            }
            cursor += (int) advance;
        }
    }

    private static AssetStorageException invalid() {
        return new AssetStorageException(AssetStorageException.Reason.INVALID_IMAGE);
    }
}
