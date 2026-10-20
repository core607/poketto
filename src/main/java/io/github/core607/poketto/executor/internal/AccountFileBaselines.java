package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Acknowledged file versions of one account journal, stored beside it under their payload digest.
 * The journal names the current file, so a journal write that keeps the same versions keeps the same
 * file. Callers hold the owner's lock through publication, reads and removal. Files are immutable;
 * cache access is synchronized because independent owners can publish concurrently.
 */
final class AccountFileBaselines {
    private static final String MARKER = ".acknowledged-";
    private static final int CACHED = 16;
    private final Path root;
    private final RetainedDirectory directory;
    private final RetainedRecordFiles files;
    private final long maximum;
    private final Map<Path, Stored> cache = new LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Stored> eldest) {
            return size() > CACHED;
        }
    };

    AccountFileBaselines(Path root, RetainedDirectory directory, RetainedRecordFiles files, long maximum) {
        this.root = root;
        this.directory = directory;
        this.files = files;
        this.maximum = maximum;
    }

    /** Durably stores {@code versions} before any journal can reference the returned digest. */
    String publish(String key, UUID copyId, Map<String, RetainedFileBaseline> versions) throws IOException {
        var stored = new Stored(copyId, versions);
        Path temporary = root.resolve(key + ".pending");
        try {
            String digest = files.write(temporary, stored, maximum);
            directory.checkFile(temporary);
            Path target = path(key, digest);
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                directory.checkFile(target);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            RetainedDirectory.sync(root);
            remember(target, stored);
            return digest;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Map<String, RetainedFileBaseline> read(String key, UUID copyId, String digest) throws IOException {
        Path path = path(key, digest);
        Stored stored;
        synchronized (cache) {
            stored = cache.get(path);
        }
        if (stored == null) {
            directory.checkFile(path);
            stored = files.read(path, Stored.class, digest);
            remember(path, stored);
        }
        ProtocolValues.require(stored.copyId().equals(copyId), "file baselines", "must belong to their copy");
        return stored.files();
    }

    /** Deletes every version file of {@code key} except {@code digest}; {@code null} deletes all of them. */
    void retainOnly(String key, String digest) throws IOException {
        Path kept = digest == null ? null : path(key, digest);
        List<Path> stale = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(root, key + MARKER + "*")) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}\\" + MARKER + "[0-9a-f]{64}")) {
                    throw new IOException("Invalid acknowledged file version address");
                }
                if (!entry.equals(kept)) {
                    stale.add(entry);
                }
            }
        }
        for (Path entry : stale) {
            directory.checkFile(entry);
            Files.delete(entry);
            synchronized (cache) {
                cache.remove(entry);
            }
        }
        if (!stale.isEmpty()) {
            RetainedDirectory.sync(root);
        }
    }

    private void remember(Path path, Stored stored) {
        synchronized (cache) {
            cache.put(path, stored);
        }
    }

    private Path path(String key, String digest) {
        return root.resolve(key + MARKER + ProtocolValues.hex(digest, 64, "file baseline digest"));
    }

    record Stored(UUID copyId, Map<String, RetainedFileBaseline> files) {
        Stored {
            Objects.requireNonNull(copyId, "file baseline copy must be present");
            files = Map.copyOf(files);
            ProtocolValues.require(files.size() <= 16384, "file baselines", "must not exceed 16384 paths");
            files.forEach((path, version) -> version.requirePath(path));
        }
    }
}
