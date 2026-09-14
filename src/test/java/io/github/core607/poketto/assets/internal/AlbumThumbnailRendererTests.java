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

    private static byte[] resource(String path) throws Exception {
        try (var stream = AlbumThumbnailRendererTests.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return stream.readAllBytes();
        }
    }
}
