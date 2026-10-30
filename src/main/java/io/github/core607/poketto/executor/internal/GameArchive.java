package io.github.core607.poketto.executor.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** A fixed three-file ustar package keeps the installed service independent of Poketto's runtime code. */
final class GameArchive {
    private static final byte[] ENTRY = resource("game-entry.mjs");
    private static final byte[] RUNTIME = resource("game-runtime.mjs");

    private GameArchive() {}

    static byte[] packageInput(byte[] input) {
        var archive = new ByteArrayOutputStream(input.length + ENTRY.length + RUNTIME.length + 4096);
        file(archive, "game-entry.mjs", ENTRY);
        file(archive, "game-runtime.mjs", RUNTIME);
        file(archive, "input.json", input);
        archive.writeBytes(new byte[1024]);
        return archive.toByteArray();
    }

    private static byte[] resource(String name) {
        String path = "/io/github/core607/poketto/executor/" + name;
        try (InputStream input = GameArchive.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Application game runtime is missing " + name);
            }
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536) {
                throw new IllegalStateException("Application game runtime exceeds its package bound");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("Application game runtime could not be read", failure);
        }
    }

    private static void file(ByteArrayOutputStream output, String name, byte[] bytes) {
        byte[] header = new byte[512];
        text(header, 0, name);
        octal(header, 100, 8, 0600);
        octal(header, 108, 8, 0);
        octal(header, 116, 8, 0);
        octal(header, 124, 12, bytes.length);
        octal(header, 136, 12, 0);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        text(header, 257, "ustar\0");
        text(header, 263, "00");
        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        text(header, 148, String.format(Locale.ROOT, "%06o\0 ", checksum));
        output.writeBytes(header);
        output.writeBytes(bytes);
        output.writeBytes(new byte[(512 - bytes.length % 512) % 512]);
    }

    private static void octal(byte[] header, int offset, int length, int value) {
        text(header, offset, String.format(Locale.ROOT, "%0" + (length - 1) + "o\0", value));
    }

    private static void text(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
