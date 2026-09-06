package io.github.core607.poketto.assets.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Keeps file-channel temporary native buffers small while returning bounded contiguous image bytes. */
final class BoundedImageReads {
    private BoundedImageReads() {}

    static byte[] read(InputStream input, int maximum) throws IOException {
        if (maximum < 1) throw new IllegalArgumentException("positive image read bound required");
        byte[] block = new byte[Math.min(8192, maximum)];
        var output = new ByteArrayOutputStream(block.length);
        while (output.size() < maximum) {
            int count = input.read(block, 0, Math.min(block.length, maximum - output.size()));
            if (count < 0) break;
            if (count == 0) {
                int single = input.read();
                if (single < 0) break;
                output.write(single);
            } else output.write(block, 0, count);
        }
        return output.toByteArray();
    }
}
