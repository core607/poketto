package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.assets.AssetStorageException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AlbumThumbnailRendererTests {
    @Test
    void opaqueSourcesBecomeJpegsAndTransparentSourcesRemainPng() throws Exception {
        byte[] opaque = AlbumThumbnailRenderer.render(image("png", BufferedImage.TYPE_INT_RGB));
        byte[] transparent = AlbumThumbnailRenderer.render(image("png", BufferedImage.TYPE_INT_ARGB));

        assertThat(AlbumThumbnailRenderer.validateEncoded(opaque)).isEqualTo("image/jpeg");
        assertThat(AlbumThumbnailRenderer.validateEncoded(transparent)).isEqualTo("image/png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(opaque)).getWidth())
                .isLessThanOrEqualTo(640);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(opaque)).getHeight())
                .isLessThanOrEqualTo(640);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(transparent)).getWidth())
                .isLessThanOrEqualTo(640);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(transparent)).getHeight())
                .isLessThanOrEqualTo(640);
    }

    @Test
    void sourceDimensionsAreRejectedBeforeDecoding() throws Exception {
        byte[] oversized = image("png", 16385, 1, BufferedImage.TYPE_INT_RGB);

        assertThatThrownBy(() -> AlbumThumbnailRenderer.render(oversized))
                .isInstanceOf(AssetStorageException.class)
                .extracting("reason")
                .isEqualTo(AssetStorageException.Reason.INVALID_IMAGE);
    }

    @Test
    void appliesJpegExifOrientationAfterSubsampling() throws Exception {
        for (int orientation : new int[] {6, 8}) {
            byte[] source = withOrientation(image("jpg", 800, 400, BufferedImage.TYPE_INT_RGB), orientation);

            var thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(AlbumThumbnailRenderer.render(source)));
            assertThat(thumbnail.getWidth()).isEqualTo(320);
            assertThat(thumbnail.getHeight()).isEqualTo(640);
        }
    }

    @Test
    void readsPngExifOrientationInsteadOfRejectingThePreview() throws Exception {
        byte[] png = image("png", 800, 400, BufferedImage.TYPE_INT_RGB);
        var cases = java.util.Map.of(
                tiff(ByteOrder.BIG_ENDIAN, 1), new int[] {640, 320},
                tiff(ByteOrder.LITTLE_ENDIAN, null), new int[] {640, 320},
                tiff(ByteOrder.BIG_ENDIAN, 6), new int[] {320, 640});
        for (var entry : cases.entrySet()) {
            byte[] source = withChunk(png, "eXIf", entry.getKey());

            var thumbnail = ImageIO.read(new java.io.ByteArrayInputStream(AlbumThumbnailRenderer.render(source)));
            assertThat(new int[] {thumbnail.getWidth(), thumbnail.getHeight()}).isEqualTo(entry.getValue());
        }
    }

    @Test
    void unreadableOrAmbiguousExifPayloadsStillRejectThePreview() throws Exception {
        byte[] png = image("png", 800, 400, BufferedImage.TYPE_INT_RGB);
        byte[] unknownOrder = tiff(ByteOrder.BIG_ENDIAN, 1);
        unknownOrder[0] = 'X';
        byte[] outOfRange = tiff(ByteOrder.BIG_ENDIAN, 9);
        byte[] twice =
                withChunk(withChunk(png, "eXIf", tiff(ByteOrder.BIG_ENDIAN, 1)), "eXIf", tiff(ByteOrder.BIG_ENDIAN, 1));

        for (byte[] source :
                new byte[][] {withChunk(png, "eXIf", unknownOrder), withChunk(png, "eXIf", outOfRange), twice}) {
            assertThatThrownBy(() -> AlbumThumbnailRenderer.render(source))
                    .isInstanceOf(AssetStorageException.class)
                    .extracting("reason")
                    .isEqualTo(AssetStorageException.Reason.INVALID_IMAGE);
        }
    }

    @Test
    void readsWebpExifOrientationInsteadOfRejectingThePreview() throws Exception {
        byte[] webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        byte[] payload = tiff(ByteOrder.LITTLE_ENDIAN, 1);
        var source = ByteBuffer.allocate(webp.length + 8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        source.put(webp).put("EXIF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        source.putInt(payload.length).put(payload);
        source.putInt(4, source.capacity() - 8);

        assertThat(AlbumThumbnailRenderer.validateEncoded(AlbumThumbnailRenderer.render(source.array())))
                .isIn("image/png", "image/jpeg");
    }

    @Test
    void decodesWebpAndRejectsSourcesOverItsIndependentRasterBound() throws Exception {
        byte[] source = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        byte[] thumbnail = AlbumThumbnailRenderer.render(source);
        assertThat(AlbumThumbnailRenderer.validateEncoded(thumbnail)).isIn("image/png", "image/jpeg");

        byte[] oversized = source.clone();
        ByteBuffer.wrap(oversized)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(26, (short) 2049)
                .putShort(28, (short) 2049);
        assertThatThrownBy(() -> AlbumThumbnailRenderer.render(oversized))
                .isInstanceOf(AssetStorageException.class)
                .extracting("reason")
                .isEqualTo(AssetStorageException.Reason.INVALID_IMAGE);
    }

    @Test
    void decodesNontrivialLosslessWebpAndRejectsItsOversizedSourceRaster() throws Exception {
        byte[] source = resource("/images/lossless-vp8l.webp");
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(source));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isGreaterThan(1);
        assertThat(decoded.getHeight()).isGreaterThan(1);
        assertThat(AlbumThumbnailRenderer.validateEncoded(AlbumThumbnailRenderer.render(source)))
                .isIn("image/png", "image/jpeg");

        byte[] oversized = source.clone();
        int dimensions = 2048 | (2048 << 14);
        ByteBuffer.wrap(oversized, 21, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(dimensions);
        assertThatThrownBy(() -> AlbumThumbnailRenderer.render(oversized))
                .isInstanceOf(AssetStorageException.class)
                .extracting("reason")
                .isEqualTo(AssetStorageException.Reason.INVALID_IMAGE);
    }

    private static byte[] image(String format, int type) throws Exception {
        return image(format, 800, 400, type);
    }

    private static byte[] image(String format, int width, int height, int type) throws Exception {
        var image = new BufferedImage(width, height, type);
        image.setRGB(0, 0, type == BufferedImage.TYPE_INT_ARGB ? 0x80112233 : 0x00112233);
        try (var output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, format, output)).isTrue();
            return output.toByteArray();
        }
    }

    private static byte[] withOrientation(byte[] jpeg, int orientation) throws Exception {
        var exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        exif.put(new byte[] {'E', 'x', 'i', 'f', 0, 0, 'I', 'I'});
        exif.putShort((short) 42).putInt(8).putShort((short) 1);
        exif.putShort((short) 0x0112)
                .putShort((short) 3)
                .putInt(1)
                .putShort((short) orientation)
                .putShort((short) 0);
        exif.putInt(0);
        int segmentLength = exif.array().length + 2;
        try (var output = new ByteArrayOutputStream()) {
            output.write(jpeg, 0, 2);
            output.write(0xff);
            output.write(0xe1);
            output.write(segmentLength >>> 8);
            output.write(segmentLength);
            output.write(exif.array());
            output.write(jpeg, 2, jpeg.length - 2);
            return output.toByteArray();
        }
    }

    /** A bare TIFF payload whose first directory holds only the orientation tag, or nothing when null. */
    private static byte[] tiff(ByteOrder order, Integer orientation) {
        int entries = orientation == null ? 0 : 1;
        var tiff = ByteBuffer.allocate(8 + 2 + 12 * entries + 4).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'});
        tiff.putShort((short) 42).putInt(8).putShort((short) entries);
        if (orientation != null) {
            tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1);
            tiff.putShort((short) (int) orientation).putShort((short) 0);
        }
        tiff.putInt(0);
        return tiff.array();
    }

    /** Inserts a CRC-correct chunk right after IHDR, where encoders place eXIf. */
    private static byte[] withChunk(byte[] png, String type, byte[] data) throws Exception {
        int afterHeader = 8 + 12 + 13;
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        try (var output = new ByteArrayOutputStream()) {
            output.write(png, 0, afterHeader);
            output.write(ByteBuffer.allocate(4).putInt(data.length).array());
            output.write(name);
            output.write(data);
            output.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
            output.write(png, afterHeader, png.length - afterHeader);
            return output.toByteArray();
        }
    }

    private static byte[] resource(String path) throws Exception {
        try (var stream = AlbumThumbnailRendererTests.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return stream.readAllBytes();
        }
    }
}
