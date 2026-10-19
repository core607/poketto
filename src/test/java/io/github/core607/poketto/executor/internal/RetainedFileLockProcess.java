package io.github.core607.poketto.executor.internal;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Independent JVM observes the OS lock; Java's FileLock validity alone cannot detect a lost POSIX lock. */
public final class RetainedFileLockProcess {
    public static void main(String[] args) throws Exception {
        try (var channel = FileChannel.open(
                        Path.of(args[0]),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) {
                System.out.println("BUSY");
                return;
            }
            System.out.println("HELD");
            System.out.flush();
            System.in.read();
        }
    }
}
