package io.github.core607.poketto.content.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/** Host-prepared changes at a checked base; copied objects avoid text-size bounds on directory moves. */
record RepositoryCandidateChanges(
        Map<String, byte[]> replacements, Map<String, ObjectEntry> copies, Set<String> deletions, boolean structural) {
    Set<String> paths() {
        Set<String> result = new HashSet<>(replacements.keySet());
        result.addAll(copies.keySet());
        result.addAll(deletions);
        return result;
    }

    /** Length-framed, ordered operations bind a preparation without depending on map iteration order. */
    String sha256() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for prepared repository changes", unavailable);
        }
        frame(digest, "repository-changes-v1");
        frame(digest, Boolean.toString(structural));
        for (String path : replacements.keySet().stream().sorted().toList()) {
            frame(digest, "replace");
            frame(digest, path);
            frame(digest, replacements.get(path));
        }
        for (String path : copies.keySet().stream().sorted().toList()) {
            ObjectEntry copy = copies.get(path);
            frame(digest, "copy");
            frame(digest, path);
            frame(digest, copy.objectId().name());
            frame(digest, Integer.toString(copy.mode().getBits()));
        }
        for (String path : deletions.stream().sorted().toList()) {
            frame(digest, "delete");
            frame(digest, path);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void frame(MessageDigest digest, String text) {
        frame(digest, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void frame(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    record ObjectEntry(ObjectId objectId, FileMode mode) {}
}
