package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Node;

/** Orientation transforms run only on the already subsampled raster. */
final class ThumbnailOrientation {
    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

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

    /**
     * The orientation of the EXIF payload in a PNG eXIf or WebP EXIF chunk, which ImageIO does not
     * interpret: 0 without such a chunk, 1 when its payload has no orientation tag. A payload that
     * cannot be read safely rejects the preview, so the original stays the only delivery.
     */
    static int embedded(byte[] image, String mediaType) {
        boolean png = mediaType.equals("image/png");
        if (!png && !mediaType.equals("image/webp")) {
            return 0;
        }
        int orientation = 0;
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
                // Both containers allow one EXIF payload; a second one leaves the authored orientation ambiguous.
                if (orientation != 0) {
                    throw invalid();
                }
                orientation = tiffOrientation(image, cursor + 8, (int) length);
            }
            long advance = overhead + length + (png ? 0 : length % 2);
            if (advance > image.length - cursor) {
                throw invalid();
            }
            cursor += (int) advance;
        }
        return orientation;
    }

    /** Reads tag 0x0112 from the first image directory of a TIFF payload, with or without an Exif header. */
    private static int tiffOrientation(byte[] image, int offset, int length) {
        if (length >= 6 && Arrays.equals(image, offset, offset + 6, EXIF_HEADER, 0, 6)) {
            offset += 6;
            length -= 6;
        }
        if (length < 8) {
            throw invalid();
        }
        ByteOrder order;
        if (image[offset] == 'I' && image[offset + 1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (image[offset] == 'M' && image[offset + 1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw invalid();
        }
        ByteBuffer tiff = ByteBuffer.wrap(image, offset, length).slice().order(order);
        long directory = Integer.toUnsignedLong(tiff.getInt(4));
        if (tiff.getShort(2) != 42 || directory < 8 || directory > length - 2) {
            throw invalid();
        }
        int entries = Short.toUnsignedInt(tiff.getShort((int) directory));
        if (directory + 2 + 12L * entries > length) {
            throw invalid();
        }
        for (int index = 0; index < entries; index++) {
            int entry = (int) directory + 2 + 12 * index;
            if (Short.toUnsignedInt(tiff.getShort(entry)) != 0x0112) {
                continue;
            }
            int value = Short.toUnsignedInt(tiff.getShort(entry + 8));
            if (tiff.getShort(entry + 2) != 3 || tiff.getInt(entry + 4) != 1 || value < 1 || value > 8) {
                throw invalid();
            }
            return value;
        }
        return 1;
    }

    private static AssetStorageException invalid() {
        return new AssetStorageException(AssetStorageException.Reason.INVALID_IMAGE);
    }
}
