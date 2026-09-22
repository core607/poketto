package io.github.core607.poketto.assets;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Fixed browser media types. Metadata selects a candidate; verified bytes must match its container. */
public enum MediaPlayback {
    MP3("audio/mpeg", "audio"),
    WAV("audio/wav", "audio"),
    MP4_AUDIO("audio/mp4", "audio"),
    MP4_VIDEO("video/mp4", "video"),
    WEBM_AUDIO("audio/webm", "audio"),
    WEBM_VIDEO("video/webm", "video");

    private final String mediaType;
    private final String kind;

    MediaPlayback(String mediaType, String kind) {
        this.mediaType = mediaType;
        this.kind = kind;
    }

    public String mediaType() {
        return mediaType;
    }

    public String kind() {
        return kind;
    }

    public static Optional<MediaPlayback> forType(String type) {
        if ("audio/wave".equals(type) || "audio/x-wav".equals(type)) {
            return Optional.of(WAV);
        }
        return Arrays.stream(values())
                .filter(value -> value.mediaType.equals(type))
                .findFirst();
    }

    /** Called only downstream of original digest verification; retains at most 4096 prefix bytes. */
    OutputStream select(OutputStream output, long size, long start, long length) {
        if (size <= 0 || start < 0 || length <= 0 || start > size - length) {
            throw new IllegalArgumentException("playback range must select bytes inside the original");
        }
        return new OutputStream() {
            final byte[] prefix = new byte[(int) Math.min(size, 4096)];
            int buffered;
            long position;
            boolean validated;

            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value});
            }

            @Override
            public void write(byte[] bytes, int offset, int count) throws IOException {
                if (!validated) {
                    int take = Math.min(count, prefix.length - buffered);
                    System.arraycopy(bytes, offset, prefix, buffered, take);
                    buffered += take;
                    offset += take;
                    count -= take;
                    if (buffered < prefix.length) {
                        return;
                    }
                    if (!matches(prefix)) {
                        throw new IllegalArgumentException("original does not match a supported playback container");
                    }
                    validated = true;
                    selected(prefix, 0, prefix.length);
                }
                selected(bytes, offset, count);
            }

            private void selected(byte[] bytes, int offset, int count) throws IOException {
                long from = Math.max(position, start);
                long to = Math.min(position + count, start + length);
                if (to > from) {
                    output.write(bytes, offset + (int) (from - position), (int) (to - from));
                }
                position += count;
            }
        };
    }

    private boolean matches(byte[] bytes) {
        return switch (this) {
            case WAV -> at(bytes, 0, "RIFF") && at(bytes, 8, "WAVE");
            case MP3 -> at(bytes, 0, "ID3") || mpegFrame(bytes);
            case MP4_AUDIO, MP4_VIDEO -> mp4(bytes);
            case WEBM_AUDIO, WEBM_VIDEO -> webm(bytes);
        };
    }

    private static boolean mp4(byte[] bytes) {
        if (bytes.length < 16 || !at(bytes, 4, "ftyp")) {
            return false;
        }
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt());
        if (size < 16 || size > bytes.length || size % 4 != 0) {
            return false;
        }
        if (at(bytes, 8, "mp4") || at(bytes, 8, "M4A ")) {
            return true;
        }
        for (int offset = 16; offset < size; offset += 4) {
            if (at(bytes, offset, "mp4") || at(bytes, offset, "M4A ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean webm(byte[] bytes) {
        if (bytes.length < 4 || ByteBuffer.wrap(bytes).getInt() != 0x1a45dfa3) {
            return false;
        }
        for (int offset = 4; offset + 7 <= Math.min(bytes.length, 48); offset++) {
            // The bounded DocType signature, not a declaration supplied alongside the upload.
            if (bytes[offset] == 0x42
                    && bytes[offset + 1] == (byte) 0x82
                    && bytes[offset + 2] == (byte) 0x84
                    && at(bytes, offset + 3, "webm")) {
                return true;
            }
        }
        return false;
    }

    private static boolean mpegFrame(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != (byte) 0xff) {
            return false;
        }
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        return (second & 0xe0) == 0xe0
                && (second & 0x18) != 0x08
                && (second & 0x06) == 0x02
                && (third & 0xf0) != 0
                && (third & 0xf0) != 0xf0
                && (third & 0x0c) != 0x0c;
    }

    private static boolean at(byte[] bytes, int offset, String text) {
        byte[] expected = text.getBytes(StandardCharsets.US_ASCII);
        return offset + expected.length <= bytes.length
                && Arrays.equals(bytes, offset, offset + expected.length, expected, 0, expected.length);
    }
}
