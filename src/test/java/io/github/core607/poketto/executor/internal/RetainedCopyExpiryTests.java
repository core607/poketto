package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RetainedCopyExpiryTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private static final RetainedCopyStore.Limits LIMITS =
            new RetainedCopyStore.Limits(2, 4096, 32768, 0, Duration.ofHours(1));

    @TempDir
    Path directory;

    @Test
    void expirySkipsHeldWritersPreservesLiveRecordsAndReclaimsTheSlotAfterRelease() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        RetainedCopyRecord expired = record(60000), live = record(120000);
        store.create(expired);
        store.create(live);
        var collector = new RetainedCopyStore(root, LIMITS, Clock.offset(CLOCK, Duration.ofMinutes(1)));
        try (var writer = store.writer(expired.owner(), expired.copyId())) {
            assertThat(collector.collectExpired()).isZero();
            writer.requireValid();
            assertReason(() -> collector.read(expired.owner(), expired.copyId()), RetainedCopyException.Reason.EXPIRED);
            assertReason(() -> store.writer(expired.owner(), expired.copyId()), RetainedCopyException.Reason.BUSY);
            assertReason(() -> collector.create(record(120000)), RetainedCopyException.Reason.LIMIT);
        }
        assertThat(collector.collectExpired()).isEqualTo(1);
        assertReason(() -> collector.read(expired.owner(), expired.copyId()), RetainedCopyException.Reason.MISSING);
        assertThat(collector.read(live.owner(), live.copyId()).copyId()).isEqualTo(live.copyId());
        collector.create(record(120000));
        assertThat(collector.collectExpired()).isZero();
        try (var entries = Files.list(root)) {
            assertThat(entries.filter(path -> path.getFileName().toString().startsWith(".writer-"))
                            .count())
                    .isZero();
        }
    }

    @Test
    void corruptExpiredRecordsAreNotDeletedOnAnUnverifiedExpiryValue() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        store.create(record(60000));
        Path file;
        try (var files = Files.list(root)) {
            file = files.filter(path -> path.toString().endsWith(".record"))
                    .findFirst()
                    .orElseThrow();
        }
        byte[] damaged = Files.readAllBytes(file);
        damaged[damaged.length - 1] ^= 1;
        Files.write(file, damaged);
        var collector = new RetainedCopyStore(root, LIMITS, Clock.offset(CLOCK, Duration.ofMinutes(1)));
        assertReason(collector::collectExpired, RetainedCopyException.Reason.UNAVAILABLE);
        assertThat(Files.readAllBytes(file)).containsExactly(damaged);
    }

    @Test
    void configuredMaintenanceCollectsExpiredRecordsWithoutAnotherExecutionRequest() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        RetainedCopyRecord expired = record(60000);
        store.create(expired);
        var collector = new RetainedCopyStore(root, LIMITS, Clock.offset(CLOCK, Duration.ofMinutes(1)));
        try (var maintenance = new ExecutorRetentionConfiguration().retainedCopyMaintenance(collector)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                try {
                    collector.read(expired.owner(), expired.copyId());
                    throw new AssertionError("Expired metadata became readable");
                } catch (RetainedCopyException failure) {
                    if (failure.reason() == RetainedCopyException.Reason.MISSING) {
                        break;
                    }
                    assertThat(failure.reason())
                            .isIn(RetainedCopyException.Reason.EXPIRED, RetainedCopyException.Reason.BUSY);
                }
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(20);
            }
        }
    }

    @Test
    void aWriterInAnotherProcessPreventsExpiryUntilThatProcessReleasesIt() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        RetainedCopyRecord expired = record(60000);
        store.create(expired);
        Path signal = directory.resolve("writer-ready");
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        RetainedStoreProcessProbe.class.getName(),
                        "hold-writer",
                        root.toString(),
                        expired.owner().subjectId().toString(),
                        expired.owner().workspaceId().toString(),
                        expired.copyId().toString(),
                        signal.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        var collector = new RetainedCopyStore(root, LIMITS, Clock.offset(CLOCK, Duration.ofMinutes(1)));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(signal).hasContent("LOCKED");
            assertThat(collector.collectExpired()).isZero();
            assertReason(() -> store.writer(expired.owner(), expired.copyId()), RetainedCopyException.Reason.BUSY);
            child.getOutputStream().close();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(collector.collectExpired()).isEqualTo(1);
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RetainedCopyRecord record(long lifetime) {
        var state = new SelectedFileSaves.State("1".repeat(40));
        return new RetainedCopyRecord(
                1,
                new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(),
                0,
                1,
                "a".repeat(64),
                true,
                null,
                CLOCK.millis() + lifetime,
                new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                new RetainedCopyRecord.Checkpoint(UUID.randomUUID(), "b".repeat(64), 1, state.snapshot()),
                null,
                null);
    }

    private static void assertReason(Runnable operation, RetainedCopyException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        RetainedCopyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
