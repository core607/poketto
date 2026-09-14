package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryFile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Host-owned three-way inputs, including non-text paths and remote deletions. */
record WorkspaceSyncInputs(
        String previousCommit,
        String remoteCommit,
        Map<String, RepositoryFile> baseline,
        Map<String, RepositoryFile> remote,
        List<String> paths) {
    static final int MAX_PATHS = 16384;
    static final long MAX_TEXT_BYTES = 32L * 1024 * 1024;

    WorkspaceSyncInputs {
        baseline = Map.copyOf(baseline);
        remote = Map.copyOf(remote);
        paths = List.copyOf(paths);
    }

    static final class Collector {
        private final String previousCommit;
        private final String remoteCommit;
        private final Map<String, RepositoryFile> baseline = new TreeMap<>();
        private final Map<String, RepositoryFile> remote = new TreeMap<>();
        private final TreeSet<String> paths = new TreeSet<>();
        private final long started = System.nanoTime();
        private long textBytes;

        Collector(String previousCommit, String remoteCommit) {
            this.previousCommit = previousCommit;
            this.remoteCommit = remoteCommit;
        }

        void baseline(RepositoryFile file) {
            accept(baseline, file);
        }

        void remote(RepositoryFile file) {
            accept(remote, file);
        }

        private void accept(Map<String, RepositoryFile> files, RepositoryFile file) {
            requireTime();
            paths.add(file.path());
            if (paths.size() > MAX_PATHS) {
                throw new IllegalArgumentException("workspace synchronization exceeds its path bound");
            }
            RepositoryFile previous = files.remove(file.path());
            textBytes -= bytes(previous);
            if (!file.expectedAbsence()) {
                textBytes += bytes(file);
                if (textBytes > MAX_TEXT_BYTES) {
                    throw new IllegalArgumentException("workspace synchronization exceeds its text byte bound");
                }
                files.put(file.path(), file);
            }
        }

        WorkspaceSyncInputs finish() {
            requireTime();
            return new WorkspaceSyncInputs(previousCommit, remoteCommit, baseline, remote, List.copyOf(paths));
        }

        private void requireTime() {
            if (System.nanoTime() - started > Duration.ofSeconds(20).toNanos()) {
                throw new IllegalArgumentException("workspace synchronization input traversal timed out");
            }
        }

        private static long bytes(RepositoryFile file) {
            return file == null
                    ? 0
                    : file.source()
                            .map(source -> source.getBytes(StandardCharsets.UTF_8).length)
                            .orElse(0);
        }
    }
}
