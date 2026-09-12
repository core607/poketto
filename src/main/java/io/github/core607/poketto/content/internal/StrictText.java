package io.github.core607.poketto.content.internal;

import java.nio.ByteBuffer;
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
}
