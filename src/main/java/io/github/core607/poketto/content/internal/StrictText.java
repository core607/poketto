package io.github.core607.poketto.content.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns repository bytes into text, or refuses. Decoding is strict everywhere on purpose: the
 * default replaces a malformed sequence with U+FFFD, which would let two different byte sequences
 * decode to the same path or the same front matter, and a caller comparing the decoded text would
 * never see that the bytes differed.
 */
public final class StrictText {

    private StrictText() {}

    /**
     * Decodes UTF-8 with no substitution. Callers translate the failure themselves, because what an
     * undecodable file means differs: a document is invalid content, a publishing policy is a
     * closed policy, and a media index is a refusal to trust the index at all.
     */
    public static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /**
     * Encodes text to UTF-8 with no substitution, refusing anything longer than {@code maxBytes}
     * with {@code tooLarge} as its message. The bound is checked on the encoded buffer before the
     * bytes are copied out of it, so text that encodes past the bound costs one buffer rather than
     * two. Callers translate the coding failure themselves for the same reason they do on the way
     * in: what unencodable text means belongs to whoever asked for it to be written.
     */
    static byte[] utf8(String source, int maxBytes, String tooLarge) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(source));
        if (encoded.remaining() > maxBytes) {
            throw new IllegalArgumentException(tooLarge);
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }
}
