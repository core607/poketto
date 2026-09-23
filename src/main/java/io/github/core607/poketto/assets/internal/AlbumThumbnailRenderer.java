package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImagePreviewPolicy;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** First-frame, metadata-free thumbnails; callers reserve image memory before entering. */
public final class AlbumThumbnailRenderer {
    public static final String REPRESENTATION = "album-640-v1";
    public static final int MAX_DIMENSION = 640;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long MAX_DECODED_PIXELS = 4_000_000;

    private AlbumThumbnailRenderer() {}

    public static byte[] render(byte[] original) {
        String mediaType = ImagePreviewPolicy.validate(original);
        int embedded = ThumbnailOrientation.embedded(original, mediaType);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, false);
                // A container EXIF payload is authoritative; otherwise the decoder's metadata decides.
                int orientation = embedded != 0 ? embedded : ThumbnailOrientation.read(reader);
                return encode(ThumbnailOrientation.apply(read(reader, mediaType), orientation));
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            var failure = invalid();
            failure.initCause(exception);
            throw failure;
        }
    }

    private static BufferedImage read(ImageReader reader, String mediaType) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        requireSourceDimensions(width, height);
        // Bound WebP source rasters independently of decoder subsampling behavior.
        if (mediaType.equals("image/webp") && (long) width * height > MAX_DECODED_PIXELS) {
            throw invalid();
        }
        int sample = Math.max(1, Math.max(width, height) / MAX_DIMENSION);
        var parameters = reader.getDefaultReadParam();
        parameters.setSourceSubsampling(sample, sample, 0, 0);
        BufferedImage decoded = reader.read(0, parameters);
        if (decoded == null) {
            throw invalid();
        }
        if ((long) decoded.getWidth() * decoded.getHeight() > MAX_DECODED_PIXELS) {
            decoded.flush();
            throw invalid();
        }
        return decoded;
    }

    private static void requireSourceDimensions(int width, int height) {
        boolean valid =
                width > 0 && height > 0 && width <= 16384 && height <= 16384 && (long) width * height <= 40_000_000;
        if (!valid) {
            throw invalid();
        }
    }

    private static byte[] encode(BufferedImage decoded) throws IOException {
        double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(decoded.getWidth(), decoded.getHeight()));
        int width = Math.max(1, (int) Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(decoded.getHeight() * scale));
        boolean alpha = decoded.getColorModel().hasAlpha();
        var thumbnail =
                new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(decoded, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
            decoded.flush();
        }
        try (var output = new ByteArrayOutputStream()) {
            if (alpha) {
                if (!ImageIO.write(thumbnail, "png", output)) {
                    throw invalid();
                }
            } else {
                writeJpeg(thumbnail, output);
            }
            if (output.size() > MAX_BYTES) {
                throw invalid();
            }
            return output.toByteArray();
        } finally {
            thumbnail.flush();
        }
    }

    private static void writeJpeg(BufferedImage image, ByteArrayOutputStream output) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw invalid();
        }
        ImageWriter writer = writers.next();
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.82f);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
    }

    public static String validateEncoded(byte[] image) {
        if (image.length > MAX_BYTES) {
            throw invalid();
        }
        String mediaType = ImagePreviewPolicy.validate(image);
        boolean supported = mediaType.equals("image/png") || mediaType.equals("image/jpeg");
        if (!supported) {
            throw invalid();
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                boolean bounded = reader.getWidth(0) <= MAX_DIMENSION && reader.getHeight(0) <= MAX_DIMENSION;
                if (!bounded) {
                    throw invalid();
                }
                return mediaType;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            var failure = invalid();
            failure.initCause(exception);
            throw failure;
        }
    }

    private static AssetStorageException invalid() {
        return new AssetStorageException(AssetStorageException.Reason.INVALID_IMAGE);
    }
}
