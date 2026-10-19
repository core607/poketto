package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
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

    @Test
    void localWaitersDoNotCreateBusyExceptionsWhileTheHolderMakesProgress() throws Exception {
        Path path = root.resolve("index.lock");
        Path events = root.resolve("contention.jfr");
        var started = new CountDownLatch(12);
        var waiters = new ArrayList<Future<Boolean>>();
        try (var held = RetainedFileLocks.acquire(path, () -> open(path));
                var tasks = Executors.newVirtualThreadPerTaskExecutor();
                var recording = new Recording()) {
            recording.enable("jdk.JavaExceptionThrow").withoutStackTrace();
            recording.start();
            for (int i = 0; i < 12; i++) {
                waiters.add(tasks.submit(() -> {
                    Thread.currentThread().setName("retained-index-waiter");
                    started.countDown();
                    try (var next = RetainedFileLocks.await(path, () -> open(path), Duration.ofSeconds(10))) {
                        next.requireValid();
                        return true;
                    }
                }));
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiters.getFirst().get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            recording.stop();
            recording.dump(events);
            held.close();
            for (var waiter : waiters) {
                assertThat(waiter.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        long busyExceptions = RecordingFile.readAllEvents(events).stream()
                .filter(event -> event.getThread().getJavaName().equals("retained-index-waiter")
                        && event.getClass("thrownClass").getName().equals(RetainedCopyException.class.getName()))
                .count();
        assertThat(busyExceptions)
                .as("exceptions created by waiting local callers")
                .isZero();
    }

    @Test
    void timedOutWaiterDoesNotOpenACompetingChannelOrStrandThePath() throws Exception {
        Path path = root.resolve("index.lock");
        var opens = new AtomicInteger();
        RetainedFileLocks.Opener opener = () -> {
            opens.incrementAndGet();
            return open(path);
        };
        try (var held = RetainedFileLocks.acquire(path, opener)) {
            assertThatThrownBy(() -> RetainedFileLocks.await(path, opener, Duration.ofMillis(100)))
                    .isInstanceOfSatisfying(
                            RetainedCopyException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.BUSY));
            assertThat(opens.get()).isEqualTo(1);
            held.requireValid();
        }
        try (var next = RetainedFileLocks.await(path, opener, Duration.ofSeconds(1))) {
            next.requireValid();
            assertThat(opens.get()).isEqualTo(2);
        }
    }

    @Test
    void interruptedWaiterKeepsItsInterruptAndDoesNotStrandThePath() throws Exception {
        Path path = root.resolve("index.lock");
        var started = new CountDownLatch(1);
        var result = new FutureTask<>(() -> {
            started.countDown();
            assertThatThrownBy(() -> RetainedFileLocks.await(path, () -> open(path), Duration.ofSeconds(10)))
                    .isInstanceOfSatisfying(RetainedCopyException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.BUSY);
                        assertThat(failure.getCause()).isInstanceOf(InterruptedException.class);
                    });
            return Thread.currentThread().isInterrupted();
        });
        try (var held = RetainedFileLocks.acquire(path, () -> open(path))) {
            Thread waiter = Thread.ofVirtual().start(result);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                waiter.interrupt();
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
                held.requireValid();
            } finally {
                waiter.interrupt();
                waiter.join(Duration.ofSeconds(5));
            }
        }
        try (var next = RetainedFileLocks.acquire(path, () -> open(path))) {
            next.requireValid();
        }
    }

    @Test
    void failedOpenReleasesItsReservation() throws Exception {
        Path path = root.resolve("index.lock");
        var failure = new IOException("fixture open failure");
        assertThatThrownBy(() -> RetainedFileLocks.await(
                        path,
                        () -> {
                            throw failure;
                        },
                        Duration.ofSeconds(1)))
                .isSameAs(failure);
        try (var next = RetainedFileLocks.acquire(path, () -> open(path))) {
            next.requireValid();
        }
    }

    @Test
    void closingPreviousHolderAgainCannotReleaseTheCurrentHolder() throws Exception {
        Path path = root.resolve("index.lock");
        try (var first = RetainedFileLocks.acquire(path, () -> open(path))) {
            first.close();
            try (var next = RetainedFileLocks.acquire(path, () -> open(path))) {
                first.close();
                assertThatThrownBy(() -> RetainedFileLocks.acquire(path, () -> open(path)))
                        .isInstanceOfSatisfying(
                                RetainedCopyException.class,
                                failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.BUSY));
                next.requireValid();
            }
        }
    }

    @Test
    void localRefusalDoesNotReleaseTheOperatingSystemLock() throws Exception {
        Path path = root.resolve("index.lock");
        try (var held = RetainedFileLocks.acquire(path, () -> open(path))) {
            assertThatThrownBy(() -> RetainedFileLocks.acquire(path, () -> open(path)))
                    .isInstanceOf(RetainedCopyException.class);
            Process other = foreignHolder(path);
            try {
                assertThat(other.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(other.exitValue()).isZero();
                assertThat(other.inputReader().readLine()).isEqualTo("BUSY");
                held.requireValid();
            } finally {
                other.destroyForcibly();
                assertThat(other.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void waitsForAnotherProcessToReleaseTheFileLock() throws Exception {
        Path path = root.resolve("index.lock");
        Process other = foreignHolder(path);
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var output = tasks.submit(() -> other.inputReader().readLine());
                assertThat(output.get(5, TimeUnit.SECONDS)).isEqualTo("HELD");
                assertThatThrownBy(() -> RetainedFileLocks.await(path, () -> open(path), Duration.ofMillis(100)))
                        .isInstanceOfSatisfying(
                                RetainedCopyException.class,
                                failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.BUSY));
                var waiter = tasks.submit(() -> {
                    try (var held = RetainedFileLocks.await(path, () -> open(path), Duration.ofSeconds(2))) {
                        held.requireValid();
                        return true;
                    }
                });
                assertThatThrownBy(() -> waiter.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                other.getOutputStream().write(1);
                other.getOutputStream().flush();
                assertThat(other.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(other.exitValue()).isZero();
                assertThat(waiter.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                other.destroyForcibly();
                assertThat(other.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static Process foreignHolder(Path path) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of(RetainedFileLockProcess.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        return new ProcessBuilder(java, "-cp", classpath, RetainedFileLockProcess.class.getName(), path.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static FileChannel open(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
}
