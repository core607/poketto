package io.github.core607.poketto.assets;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Bounded image-preview validation; callers separately authorize the source and reserve response memory. */
public final class ImagePreviewPolicy {
    private ImagePreviewPolicy() {}

    public static String validate(byte[] bytes) {
        if (bytes == null || bytes.length > ManagedBlobStore.MAX_UPLOAD_BYTES) {
            throw invalid();
        }
        try {
            if (isPng(bytes)) {
                return png(bytes);
            }
            if (isGif(bytes)) {
                return gif(bytes);
            }
            if (isJpeg(bytes)) {
                return jpeg(bytes);
            }
            if (isWebp(bytes)) {
                return webp(bytes);
            }
        } catch (IndexOutOfBoundsException truncated) {
            throw invalid();
        }
        throw invalid();
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 45
                && bytes[0] == (byte) 137
                && text(bytes, 1, 3).equals("PNG")
                && bytes[4] == 13
                && bytes[5] == 10
                && bytes[6] == 26
                && bytes[7] == 10;
    }

    // Every chunk must carry a valid CRC, IHDR must come first and IEND must close the file exactly.
    private static String png(byte[] bytes) {
        if (be32(bytes, 8) != 13 || !text(bytes, 12, 4).equals("IHDR")) {
            throw invalid();
        }
        dimensions(be32(bytes, 16), be32(bytes, 20));
        int cursor = 8;
        boolean data = false;
        while (cursor < bytes.length) {
            long length = be32(bytes, cursor);
            if (length > bytes.length - cursor - 12) {
                throw invalid();
            }
            String type = text(bytes, cursor + 4, 4);
            CRC32 crc = new CRC32();
            crc.update(bytes, cursor + 4, (int) length + 4);
            if (crc.getValue() != be32(bytes, cursor + 8 + (int) length)) {
                throw invalid();
            }
            data |= type.equals("IDAT");
            cursor += (int) length + 12;
            if (type.equals("IEND")) {
                if (length != 0 || cursor != bytes.length || !data) {
                    throw invalid();
                }
                return "image/png";
            }
        }
        throw invalid();
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length >= 14
                && (text(bytes, 0, 6).equals("GIF87a") || text(bytes, 0, 6).equals("GIF89a"));
    }

    private static String gif(byte[] bytes) {
        dimensions(le16(bytes, 6), le16(bytes, 8));
        if (bytes[bytes.length - 1] != 0x3b) {
            throw invalid();
        }
        return "image/gif";
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 20
                && u(bytes[0]) == 255
                && u(bytes[1]) == 216
                && u(bytes[bytes.length - 2]) == 255
                && u(bytes[bytes.length - 1]) == 217;
    }

    // Segments are walked until a start-of-frame marker supplies the dimensions; scan data before it is invalid.
    private static String jpeg(byte[] bytes) {
        int cursor = 2;
        while (cursor < bytes.length - 4) {
            if (u(bytes[cursor++]) != 255) {
                throw invalid();
            }
            while (u(bytes[cursor]) == 255) {
                cursor++;
            }
            int marker = u(bytes[cursor++]);
            if (marker == 0xda || marker == 0xd9) {
                throw invalid();
            }
            int length = (u(bytes[cursor]) << 8) | u(bytes[cursor + 1]);
            if (length < 2 || length > bytes.length - cursor) {
                throw invalid();
            }
            if (startOfFrame(marker)) {
                if (length < 8) {
                    throw invalid();
                }
                dimensions(
                        (u(bytes[cursor + 5]) << 8) | u(bytes[cursor + 6]),
                        (u(bytes[cursor + 3]) << 8) | u(bytes[cursor + 4]));
                return "image/jpeg";
            }
            cursor += length;
        }
        throw invalid();
    }

    private static boolean startOfFrame(int marker) {
        return (marker >= 0xc0 && marker <= 0xc3)
                || (marker >= 0xc5 && marker <= 0xc7)
                || (marker >= 0xc9 && marker <= 0xcb)
                || (marker >= 0xcd && marker <= 0xcf);
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 25
                && text(bytes, 0, 4).equals("RIFF")
                && text(bytes, 8, 4).equals("WEBP");
    }

    private static String webp(byte[] bytes) {
        if (le32(bytes, 4) != bytes.length - 8L) {
            throw invalid();
        }
        long chunk = le32(bytes, 16);
        if (chunk > bytes.length - 20L) {
            throw invalid();
        }
        switch (text(bytes, 12, 4)) {
            case "VP8 " -> {
                if (chunk < 10 || u(bytes[23]) != 0x9d || u(bytes[24]) != 1 || u(bytes[25]) != 0x2a) {
                    throw invalid();
                }
                dimensions(le16(bytes, 26) & 0x3fff, le16(bytes, 28) & 0x3fff);
            }
            case "VP8L" -> {
                if (chunk < 5 || u(bytes[20]) != 0x2f) {
                    throw invalid();
                }
                dimensions(
                        1 + u(bytes[21]) + ((u(bytes[22]) & 0x3f) << 8),
                        1 + (u(bytes[22]) >> 6) + (u(bytes[23]) << 2) + ((u(bytes[24]) & 0xf) << 10));
            }
            case "VP8X" -> {
                if (chunk != 10 || bytes.length < 30) {
                    throw invalid();
                }
                dimensions(1 + le24(bytes, 24), 1 + le24(bytes, 27));
            }
            default -> throw invalid();
        }
        return "image/webp";
    }

    private static void dimensions(long width, long height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384 || width * height > 40_000_000) {
            throw invalid();
        }
    }

    private static AssetStorageException invalid() {
        return new AssetStorageException(AssetStorageException.Reason.INVALID_IMAGE);
    }

    private static int u(byte value) {
        return value & 255;
    }

    private static String text(byte[] bytes, int offset, int size) {
        return new String(bytes, offset, size, StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] bytes, int offset) {
        return u(bytes[offset]) | (u(bytes[offset + 1]) << 8);
    }

    private static int le24(byte[] bytes, int offset) {
        return le16(bytes, offset) | (u(bytes[offset + 2]) << 16);
    }

    private static long le32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(le24(bytes, offset) | (u(bytes[offset + 3]) << 24));
    }

    private static long be32(byte[] bytes, int offset) {
        return ((long) u(bytes[offset]) << 24)
                | ((long) u(bytes[offset + 1]) << 16)
                | ((long) u(bytes[offset + 2]) << 8)
                | u(bytes[offset + 3]);
    }
}
