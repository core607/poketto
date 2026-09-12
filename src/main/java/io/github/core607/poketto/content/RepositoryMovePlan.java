package io.github.core607.poketto.content;

import io.github.core607.poketto.content.internal.RepositoryPathRules;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-owned local preconditions and replacement text for one immutable move base. Preparing a plan
 * neither commits nor grants publication authority. Original identities stay within its workspace.
 */
public record RepositoryMovePlan(
        WorkspaceId workspace,
        RepositoryMoveRequest request,
        Map<String, Original> originals,
        Map<String, String> relocations,
        Map<String, byte[]> replacements) {
    /** Paths one move may affect, from the atomic content moves record. */
    public static final int MAX_CHANGED_PATHS = 16_384;

    /** Replacement text one move may carry, from the atomic content moves record. */
    public static final int MAX_REPLACEMENT_BYTES = 32 * 1024 * 1024;
    /** Largest original a move may relocate, the same per-file bound the blob store applies. */
    public static final long MAX_ORIGINAL_BYTES = 128L * 1024 * 1024;

    /** Optional means an indexed original may never have been materialized in this session. */
    public record Original(String sha256, long bytes, boolean optional) {
        public Original {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || bytes < 0 || bytes > MAX_ORIGINAL_BYTES) {
                throw new IllegalArgumentException("invalid move original fingerprint");
            }
        }
    }

    public RepositoryMovePlan {
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(request);
        originals = Map.copyOf(originals);
        relocations = Map.copyOf(relocations);
        var paths = new HashSet<>(originals.keySet());
        paths.addAll(relocations.keySet());
        paths.addAll(relocations.values());
        paths.addAll(replacements.keySet());
        if (paths.size() > MAX_CHANGED_PATHS || relocations.isEmpty()) {
            throw new IllegalArgumentException("move exceeds session path capacity or has no relocations");
        }
        paths.forEach(RepositoryPathRules::validate);
        if (!originals.keySet().containsAll(relocations.keySet())) {
            throw new IllegalArgumentException("move sources need authoritative fingerprints");
        }
        long total =
                replacements.values().stream().mapToLong(value -> value.length).sum();
        if (total > MAX_REPLACEMENT_BYTES) {
            throw new IllegalArgumentException("move replacement text exceeds staging capacity");
        }
        replacements = copyBytes(replacements);
    }

    @Override
    public Map<String, byte[]> replacements() {
        return copyBytes(replacements);
    }

    private static Map<String, byte[]> copyBytes(Map<String, byte[]> values) {
        var copy = new LinkedHashMap<String, byte[]>();
        values.forEach((path, bytes) -> copy.put(path, bytes.clone()));
        return Collections.unmodifiableMap(copy);
    }
}
