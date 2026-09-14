package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedFileLocksTests {
    @TempDir
    Path root;

    @Test
    void waitsForBriefIndexContentionWithoutOpeningACompetingChannel() throws Exception {
        Path path = root.resolve("index.lock");
        var opens = new AtomicInteger();
        RetainedFileLocks.Opener opener = () -> {
            opens.incrementAndGet();
            return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        };
        try (var held = RetainedFileLocks.acquire(path, opener);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var started = new CountDownLatch(1);
            var waiter = tasks.submit(() -> {
                started.countDown();
                try (var next = RetainedFileLocks.await(path, opener, Duration.ofSeconds(2))) {
                    next.requireValid();
                    return true;
                }
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiter.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(opens.get()).isEqualTo(1);
            assertThatThrownBy(() -> RetainedFileLocks.acquire(path, opener)).isInstanceOf(RetainedCopyException.class);
            held.requireValid();
            held.close();
            assertThat(waiter.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(opens.get()).isEqualTo(2);
        }
    }
}
