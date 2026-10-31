package io.github.core607.poketto.executor.internal;

/** Immutable application-owned bytes used by a retained preparation plan. */
record NativePlanFile(long bytes, String sha256) {
    NativePlanFile {
        ProtocolValues.inRange(bytes, 0, NativeArtifacts.MAX_FILE, "plan file bytes");
        sha256 = ProtocolValues.hex(sha256, 64, "plan file digest");
    }
}
