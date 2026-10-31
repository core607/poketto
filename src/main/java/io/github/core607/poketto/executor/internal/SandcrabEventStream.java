package io.github.core607.poketto.executor.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Native SSE fields are unique and bounded across HTTP chunks, including comment-only frames. */
final class SandcrabEventStream {
    private static final int MAX_STREAM = 16 * 1024 * 1024;
    private final InputStream input;
    private final int maximum;
    private int consumed;

    SandcrabEventStream(InputStream input, int maximum) {
        this.input = new BufferedInputStream(input, 8192);
        this.maximum = maximum;
    }

    record Frame(String id, String kind, byte[] data) {}

    Frame next() throws IOException {
        String id = null;
        String kind = null;
        String data = null;
        int bytes = 0;
        while (true) {
            byte[] line = line(maximum - bytes);
            if (line == null) {
                require(bytes == 0, "Incomplete native event frame");
                throw new IOException("Native event stream ended without a terminal result");
            }
            bytes += line.length;
            String text = text(line);
            if (text.isEmpty()) {
                if (id == null && kind == null && data == null) {
                    bytes = 0;
                    continue;
                }
                require(id != null && kind != null && data != null, "Incomplete native event fields");
                return new Frame(id, kind, data.getBytes(StandardCharsets.UTF_8));
            }
            if (text.startsWith(":")) {
                continue;
            }
            int colon = text.indexOf(':');
            require(colon > 0, "Invalid native event field");
            String value = text.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (text.substring(0, colon)) {
                case "id" -> {
                    require(id == null, "Duplicate native event cursor");
                    id = value;
                }
                case "event" -> {
                    require(kind == null, "Duplicate native event kind");
                    kind = value;
                }
                case "data" -> {
                    require(data == null, "Duplicate native event data");
                    data = value;
                }
                default -> throw new IOException("Unknown native event field");
            }
        }
    }

    private byte[] line(int available) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                require(bytes.size() == 0, "Incomplete native event line");
                return null;
            }
            require(++consumed <= MAX_STREAM, "Native event stream exceeds its byte bound");
            require(bytes.size() < available, "Native event frame exceeds its byte bound");
            bytes.write(value);
            if (value == '\n') {
                return bytes.toByteArray();
            }
        }
    }

    private static String text(byte[] bytes) throws IOException {
        int length = bytes.length - 1;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Invalid native event UTF-8", malformed);
        }
    }

    static void require(boolean valid, String message) throws IOException {
        if (!valid) {
            throw new IOException(message);
        }
    }
}
