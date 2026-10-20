package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Immutable decoded files only; callers still resolve the commit and enforce each traversal's limits and grants. */
final class RepositoryBaselineCache {
    private final int maximumSnapshots;
    private final long maximumBytes;
    private final LinkedHashMap<Key, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private long occupiedBytes;

    RepositoryBaselineCache() {
        this(16, 32 * 1024 * 1024);
    }

    RepositoryBaselineCache(int maximumSnapshots, long maximumBytes) {
        if (maximumSnapshots < 1) {
            throw new IllegalArgumentException("baseline cache snapshot limit must be positive");
        }
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("baseline cache byte limit must be positive");
        }
        this.maximumSnapshots = maximumSnapshots;
        this.maximumBytes = maximumBytes;
    }

    synchronized Optional<List<RepositoryFile>> find(WorkspaceId workspace, String commit) {
        return Optional.ofNullable(snapshots.get(new Key(workspace, commit))).map(Snapshot::files);
    }

    Capture capture() {
        return new Capture(this);
    }

    /** Publish only after traversal and its sink have completed; a partial capture is not a baseline. */
    synchronized void remember(WorkspaceId workspace, String commit, Capture capture) {
        if (capture.files == null) {
            return;
        }
        var key = new Key(workspace, commit);
        Snapshot replaced = snapshots.remove(key);
        if (replaced != null) {
            occupiedBytes -= replaced.bytes();
        }
        while (snapshots.size() >= maximumSnapshots) {
            evictFirst();
        }
        snapshots.put(key, new Snapshot(capture.bytes, List.copyOf(capture.files)));
        capture.files = null;
        capture.bytes = 0;
    }

    private synchronized boolean reserve(long bytes) {
        if (bytes > maximumBytes) {
            return false;
        }
        while (bytes > maximumBytes - occupiedBytes) {
            if (snapshots.isEmpty()) {
                return false;
            }
            evictFirst();
        }
        occupiedBytes += bytes;
        return true;
    }

    private void evictFirst() {
        occupiedBytes -= snapshots.pollFirstEntry().getValue().bytes();
    }

    private synchronized void release(long bytes) {
        occupiedBytes -= bytes;
    }

    private record Key(WorkspaceId workspace, String commit) {}

    private record Snapshot(long bytes, List<RepositoryFile> files) {}

    static final class Capture implements AutoCloseable {
        private final RepositoryBaselineCache cache;
        private List<RepositoryFile> files = new ArrayList<>();
        private long bytes;

        private Capture(RepositoryBaselineCache cache) {
            this.cache = cache;
        }

        void accept(RepositoryFile file) {
            if (files == null) {
                return;
            }
            long weight = weight(file);
            if (!cache.reserve(weight)) {
                close();
                return;
            }
            files.add(file);
            bytes += weight;
        }

        @Override
        public void close() {
            cache.release(bytes);
            bytes = 0;
            files = null;
        }

        /** Charge UTF-16 text and per-file overhead, including diagnostics, without retaining Git objects or readers. */
        private static long weight(RepositoryFile file) {
            long result = 512L + 2L * file.path().length();
            result += file.source().map(source -> 2L * source.length()).orElse(0L);
            for (var diagnostic : file.diagnostics()) {
                result += 128L
                        + 2L * diagnostic.path().length()
                        + 2L * diagnostic.code().length()
                        + 2L * diagnostic.message().length();
            }
            return result;
        }
    }
}
