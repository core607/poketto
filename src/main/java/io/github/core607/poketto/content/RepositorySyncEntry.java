package io.github.core607.poketto.content;

import java.util.Objects;

/** A committed Git entry; indexed media is represented by its index rather than a Git blob. */
public record RepositorySyncEntry(String commit, String path, Kind kind, long bytes, String sha256) {
    public static final long MAX_BYTES = 128L * 1024 * 1024;

    public RepositorySyncEntry {
        if (commit == null || !commit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("blob commit must be an exact Git object ID");
        }
        RepositoryPaths.validate(path);
        Objects.requireNonNull(kind, "blob kind must be present");
        if (bytes < 0 || bytes > MAX_BYTES) {
            throw new IllegalArgumentException("blob exceeds the 128 MiB bound");
        }
        if (kind == Kind.FILE) {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("a regular blob requires its byte digest");
            }
        } else if (sha256 != null || bytes != 0) {
            throw new IllegalArgumentException("non-file entries do not carry blob bytes");
        }
    }

    public enum Kind {
        ABSENT,
        FILE,
        DIRECTORY,
        UNSUPPORTED
    }
}
