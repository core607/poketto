package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** A separate JVM proves that file visibility and exclusion do not come from an in-process cache. */
public final class RetainedStoreProcessProbe {
    private RetainedStoreProcessProbe() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        if (args[0].equals("try-lock")) {
            try (FileChannel channel = FileChannel.open(root.resolve(args[2]), WRITE, NOFOLLOW_LINKS);
                    var lock = channel.tryLock()) {
                System.out.println(lock == null ? "BUSY" : "ACQUIRED");
            }
            return;
        }
        if (args[0].equals("lock")) {
            try (FileChannel channel = FileChannel.open(root.resolve(".lock"), WRITE, NOFOLLOW_LINKS);
                    var lock = channel.lock()) {
                if (!lock.isValid()) {
                    throw new IllegalStateException("child did not acquire the retention lock");
                }
                signal(Path.of(args[2]));
                System.in.read();
            }
            return;
        }
        var clock = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
        var store = new RetainedCopyStore(
                root, new RetainedCopyStore.Limits(8, 4096, 32768, 0, Duration.ofHours(1)), clock);
        var owner = new RetainedCopyRecord.Owner(UUID.fromString(args[2]), UUID.fromString(args[3]));
        if (args[0].equals("try-writer") || args[0].equals("hold-writer")) {
            try (var lock = store.writer(owner, UUID.fromString(args[4]))) {
                lock.requireValid();
                if (args[0].equals("hold-writer")) {
                    signal(Path.of(args[5]));
                    System.in.read();
                } else {
                    System.out.println("ACQUIRED");
                }
            } catch (RetainedCopyException contention) {
                System.out.println(contention.reason());
            }
            return;
        }
        var record = store.read(owner, UUID.fromString(args[4]));
        System.out.println(record.revision() + ":" + record.generation() + ":"
                + record.acknowledged().state().originalCommit());
    }

    private static void signal(Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".retained-ready-", ".tmp");
        try {
            Files.writeString(temporary, "LOCKED");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
