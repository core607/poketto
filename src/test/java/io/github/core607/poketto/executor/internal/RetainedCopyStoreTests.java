package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositoryMoveRequest;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RetainedCopyStoreTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private static final String BASE = "1".repeat(40);
    private static final RetainedCopyStore.Limits LIMITS =
            new RetainedCopyStore.Limits(8, 4096, 32768, 0, Duration.ofHours(1));

    @TempDir
    Path directory;

    @Test
    void failedSameJvmContenderDoesNotReleaseTheOriginalProcessLock() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        Path path = root.resolve(".lock");
        try (var lock = RetainedFileLocks.acquire(path, () -> FileChannel.open(path, StandardOpenOption.WRITE))) {
            lock.requireValid();
            assertChild("BUSY", "try-lock", root.toString(), ".lock");
            assertFailure(() -> store.read(initial.owner(), initial.copyId()), RetainedCopyException.Reason.BUSY);
            assertChild("BUSY", "try-lock", root.toString(), ".lock");
        }
        assertChild("ACQUIRED", "try-lock", root.toString(), ".lock");
    }

    @Test
    void copyWriterExcludesOtherProcessesAndLeavesDifferentCopiesIndependent() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var second = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        try (var first = store.writer(initial.owner(), initial.copyId());
                var other = second.writer(initial.owner(), UUID.randomUUID())) {
            first.requireValid();
            other.requireValid();
            assertFailure(() -> second.writer(initial.owner(), initial.copyId()), RetainedCopyException.Reason.BUSY);
            assertWriterChild("BUSY", root, initial);
            assertThat(second.read(initial.owner(), initial.copyId()).generation())
                    .isEqualTo(1);
        }
        assertWriterChild("ACQUIRED", root, initial);
    }

    @Test
    void writerFilesStayBoundedAndAnActiveOrphanCannotBeReplaced() throws Exception {
        Path root = directory.resolve("retained");
        var limits = new RetainedCopyStore.Limits(1, 4096, 32768, 0, Duration.ofHours(1));
        var store = new RetainedCopyStore(root, limits, CLOCK);
        var initial = initial();
        try (var first = store.writer(initial.owner(), initial.copyId())) {
            first.requireValid();
            assertFailure(() -> store.writer(initial.owner(), UUID.randomUUID()), RetainedCopyException.Reason.LIMIT);
            assertWriterChild("BUSY", root, initial);
        }
        for (int index = 0; index < 12; index++) {
            try (var next = store.writer(initial.owner(), UUID.randomUUID())) {
                next.requireValid();
            }
        }
        try (var files = Files.list(root)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith(".writer-"))
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void discardingMetadataDoesNotRemoveAStillHeldWriterInode() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        try (var writer = store.writer(initial.owner(), initial.copyId())) {
            writer.requireValid();
            store.discard(initial.owner(), initial.copyId(), 0, 1);
            assertWriterChild("BUSY", root, initial);
        }
        assertWriterChild("ACQUIRED", root, initial);
    }

    @Test
    void latestWorkerLeaseSurvivesReopenAndCannotChangeWithoutNewGeneration() {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        var writer = new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        store.create(initial);
        for (int generation = 1; generation <= 2; generation++) {
            var next = new RetainedCopyRecord(
                    1,
                    initial.owner(),
                    initial.copyId(),
                    1,
                    generation,
                    initial.transportHash(),
                    initial.fullRead(),
                    initial.publicExport(),
                    initial.expiresAt(),
                    writer,
                    initial.acknowledged(),
                    null,
                    null,
                    initial.originalBaseline());
            if (generation == 1) {
                assertThatThrownBy(() -> store.replace(0, 1, next))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("worker lease transfer");
                assertThat(store.read(initial.owner(), initial.copyId()).writer())
                        .isEqualTo(initial.writer());
            } else {
                store.replace(0, 1, next);
            }
        }
        var reopened = new RetainedCopyStore(root, LIMITS, CLOCK).read(initial.owner(), initial.copyId());
        assertThat(reopened.writer()).isEqualTo(writer);
        assertThat(reopened.generation()).isEqualTo(2);
    }

    @Test
    void killedApplicationProcessReleasesOnlyItsWriterLock() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        Path signal = directory.resolve("writer-locked");
        Process holder = child(
                "hold-writer",
                root.toString(),
                initial.owner().subjectId().toString(),
                initial.owner().workspaceId().toString(),
                initial.copyId().toString(),
                signal.toString());
        try (var other = store.writer(initial.owner(), UUID.randomUUID())) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(signal) && holder.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(signal).hasContent("LOCKED");
            assertFailure(() -> store.writer(initial.owner(), initial.copyId()), RetainedCopyException.Reason.BUSY);
            holder.destroyForcibly();
            assertThat(holder.waitFor(15, TimeUnit.SECONDS)).isTrue();
            try (var recovered = store.writer(initial.owner(), initial.copyId())) {
                recovered.requireValid();
                other.requireValid();
            }
        } finally {
            holder.destroyForcibly();
            holder.waitFor(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void reopensExactCheckpointAndUnconfirmedCommandWithoutMutableAliases() throws Exception {
        Path root = directory.resolve("retained");
        var first = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        first.create(initial);
        var live = SelectedFileSaves.State.restore(initial.acknowledged().state());
        live.acknowledgeMove("2".repeat(40), Set.of("one.md"), Map.of());
        var pending = new RetainedCopyRecord.Command(
                UUID.randomUUID(),
                RetainedCopyRecord.Outcome.RUNNING,
                new RetainedCopyRecord.Checkpoint(
                        UUID.randomUUID(),
                        "e".repeat(64),
                        30,
                        withTextBaseline(withImport(live.snapshot(), "unconfirmed command"))));
        var next = changed(initial, 1, 1, initial.transportHash(), initial.acknowledged(), pending);
        first.replace(0, 1, next);

        var loaded = new RetainedCopyStore(root, LIMITS, CLOCK).read(initial.owner(), initial.copyId());

        assertThat(loaded.revision()).isEqualTo(1);
        assertThat(loaded.command().id()).isEqualTo(pending.id());
        assertThat(loaded.command().outcome()).isEqualTo(RetainedCopyRecord.Outcome.RUNNING);
        assertThat(loaded.acknowledged().state().baseCommit()).isEqualTo(BASE);
        assertThat(loaded.command().checkpoint().state().baseCommit()).isEqualTo("2".repeat(40));
        assertThat(loaded.command().checkpoint().state().baselines()).containsEntry("one.md", "2".repeat(40));
        assertThat(loaded.command().checkpoint().state().fileBaselines())
                .containsEntry("one.md", RetainedFileBaseline.saved("2".repeat(40), "已保存的旧正文"));
        assertThat(loaded.acknowledged().state().fileBaselines()).isEmpty();
        assertThat(loaded.command().checkpoint().id())
                .isEqualTo(pending.checkpoint().id());
        assertThat(loaded.command().checkpoint().id())
                .isNotEqualTo(loaded.acknowledged().id());
        assertThat(Files.getPosixFilePermissions(root)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(recordPath(root)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertNoPending(root);
    }

    @Test
    void rejectsOtherOwnersAndStaleWritersAfterOwnershipTransfer() {
        var store = new RetainedCopyStore(directory.resolve("retained"), LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        var otherSubject =
                new RetainedCopyRecord.Owner(UUID.randomUUID(), initial.owner().workspaceId());
        var otherWorkspace = new RetainedCopyRecord.Owner(initial.owner().subjectId(), UUID.randomUUID());
        assertFailure(() -> store.read(otherSubject, initial.copyId()), RetainedCopyException.Reason.MISSING);
        assertFailure(() -> store.read(otherWorkspace, initial.copyId()), RetainedCopyException.Reason.MISSING);
        var next = changed(initial, 1, 2, "b".repeat(64), initial.acknowledged(), null);
        store.replace(0, 1, next);
        assertFailure(() -> store.replace(0, 1, next), RetainedCopyException.Reason.STALE);
        assertFailure(() -> store.discard(initial.owner(), initial.copyId(), 0, 1), RetainedCopyException.Reason.STALE);
        assertThatThrownBy(() -> store.replace(1, 2, changed(next, 2, 2, "c".repeat(64), next.acknowledged(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("writer generation");
        assertThat(store.read(initial.owner(), initial.copyId()).transportHash())
                .isEqualTo("b".repeat(64));
    }

    @Test
    void simultaneousOwnersCannotBothReplaceTheSameRevision() throws Exception {
        Path root = directory.resolve("retained");
        var first = new RetainedCopyStore(root, LIMITS, CLOCK);
        var second = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        first.create(initial);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> compete(first, initial, "b".repeat(64), start));
            var right = executor.submit(() -> compete(second, initial, "c".repeat(64), start));
            start.countDown();
            assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(first.read(initial.owner(), initial.copyId()).generation()).isEqualTo(2);
    }

    @Test
    void aSeparateProcessReadsThePublishedRecordAndExcludesWritersWhileHoldingItsLock() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        Process reader = child(
                "read",
                root.toString(),
                initial.owner().subjectId().toString(),
                initial.owner().workspaceId().toString(),
                initial.copyId().toString());
        try {
            assertThat(reader.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(reader.exitValue()).isZero();
            assertThat(reader.inputReader().readLine()).isEqualTo("0:1:" + BASE);
        } finally {
            reader.destroyForcibly();
        }
        Path signal = directory.resolve("child-locked");
        Process holder = child("lock", root.toString(), signal.toString());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(signal) && holder.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(signal).hasContent("LOCKED");
            var next = changed(initial, 1, 1, initial.transportHash(), initial.acknowledged(), null);
            assertFailure(() -> store.replace(0, 1, next), RetainedCopyException.Reason.BUSY);
        } finally {
            holder.destroyForcibly();
            assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(store.read(initial.owner(), initial.copyId()).revision()).isZero();
    }

    @Test
    void oversizedReplacementLeavesTheAcknowledgedRecordIntact() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        var checkpoint = new RetainedCopyRecord.Checkpoint(
                UUID.randomUUID(),
                "d".repeat(64),
                10,
                withImport(initial.acknowledged().state(), "x".repeat(6000)));
        var next = changed(initial, 1, 1, initial.transportHash(), checkpoint, null);
        assertFailure(() -> store.replace(0, 1, next), RetainedCopyException.Reason.LIMIT);
        assertThat(store.read(initial.owner(), initial.copyId()).acknowledged().id())
                .isEqualTo(initial.acknowledged().id());
        assertNoPending(root);
    }

    @Test
    void aggregateQuotaIncludesTheOldRecordAndTemporaryReplacement() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        var checkpoint = new RetainedCopyRecord.Checkpoint(
                UUID.randomUUID(),
                "d".repeat(64),
                10,
                withImport(initial.acknowledged().state(), "x".repeat(2500)));
        var next = changed(initial, 1, 1, initial.transportHash(), checkpoint, null);
        Path sample = directory.resolve("sample.record");
        new RetainedRecordFiles(4096).write(sample, next, 4096);
        long budget = Files.size(recordPath(root)) + Files.size(sample) - 1;
        assertThat(budget).isGreaterThanOrEqualTo(4096);
        var tighter = new RetainedCopyStore(
                root, new RetainedCopyStore.Limits(8, 4096, budget, 0, Duration.ofHours(1)), CLOCK);
        assertFailure(() -> tighter.replace(0, 1, next), RetainedCopyException.Reason.LIMIT);
        assertThat(store.read(initial.owner(), initial.copyId()).revision()).isZero();
        assertNoPending(root);
    }

    @Test
    void reopensMovePayloadLargerThanTheDefaultJsonStringLimit() throws Exception {
        Path root = directory.resolve("retained");
        var limits = new RetainedCopyStore.Limits(8, 32L * 1024 * 1024, 64L * 1024 * 1024, 0, Duration.ofHours(1));
        var initial = initial();
        var state = SelectedFileSaves.State.restore(initial.acknowledged().state());
        byte[] payload = new byte[16 * 1024 * 1024];
        Arrays.fill(payload, (byte) 'x');
        state.move = new SessionMoves.Pending(
                new RepositoryMoveRequest(BASE, "old.md", "new.md"), payload, Set.of("old.md", "new.md"));
        var command = new RetainedCopyRecord.Command(
                UUID.randomUUID(),
                RetainedCopyRecord.Outcome.INTERRUPTED,
                new RetainedCopyRecord.Checkpoint(
                        UUID.randomUUID(), "e".repeat(64), 30, withImport(state.snapshot(), "pending move")));
        var record = changed(initial, 0, 1, initial.transportHash(), initial.acknowledged(), command);
        new RetainedCopyStore(root, limits, CLOCK).create(record);

        var restored = new RetainedCopyStore(root, limits, CLOCK).read(initial.owner(), initial.copyId());

        byte[] restoredPayload = restored.command().checkpoint().state().move().payload();
        assertThat(restoredPayload.length).isEqualTo(payload.length);
        assertThat(MessageDigest.getInstance("SHA-256").digest(restoredPayload))
                .containsExactly(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    @Test
    void corruptionIsRefusedBeforeStateIsDeserializedOrReplaced() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        Path path = recordPath(root);
        byte[] corrupted = Files.readAllBytes(path);
        corrupted[30] ^= 1;
        Files.write(path, corrupted);
        assertFailure(() -> store.read(initial.owner(), initial.copyId()), RetainedCopyException.Reason.UNAVAILABLE);
        assertFailure(() -> store.create(initial), RetainedCopyException.Reason.STALE);
        assertThat(Files.readAllBytes(path)).containsExactly(corrupted);
    }

    @Test
    void refusesSymlinkAndHardLinkAliasesWithoutTouchingTargets() throws Exception {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        Path path = recordPath(root);
        Path alias = directory.resolve("alias");
        Files.createLink(alias, path);
        assertFailure(() -> store.read(initial.owner(), initial.copyId()), RetainedCopyException.Reason.UNAVAILABLE);
        Files.delete(alias);
        Path external = directory.resolve("external");
        Files.writeString(external, "unchanged");
        Files.delete(path);
        Files.createSymbolicLink(path, external);
        assertFailure(() -> store.read(initial.owner(), initial.copyId()), RetainedCopyException.Reason.UNAVAILABLE);
        assertThat(Files.readString(external)).isEqualTo("unchanged");
        Path redirected = directory.resolve("redirected");
        Files.createSymbolicLink(redirected, root);
        assertFailure(() -> new RetainedCopyStore(redirected, LIMITS, CLOCK), RetainedCopyException.Reason.UNAVAILABLE);
    }

    @Test
    void expiryCannotBeReversedByUpdatingTheOldRecord() {
        Path root = directory.resolve("retained");
        var store = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        store.create(initial);
        var later = new RetainedCopyStore(root, LIMITS, Clock.offset(CLOCK, Duration.ofHours(2)));
        assertFailure(() -> later.read(initial.owner(), initial.copyId()), RetainedCopyException.Reason.EXPIRED);
        assertFailure(
                () -> later.replace(
                        0, 1, changed(initial, 1, 1, initial.transportHash(), initial.acknowledged(), null)),
                RetainedCopyException.Reason.EXPIRED);
        later.discard(initial.owner(), initial.copyId(), 0, 1);
        assertFailure(() -> store.read(initial.owner(), initial.copyId()), RetainedCopyException.Reason.MISSING);
    }

    private static boolean compete(
            RetainedCopyStore store, RetainedCopyRecord initial, String transport, CountDownLatch start)
            throws Exception {
        start.await();
        try {
            store.replace(0, 1, changed(initial, 1, 2, transport, initial.acknowledged(), null));
            return true;
        } catch (RetainedCopyException failure) {
            assertThat(failure.reason()).isIn(RetainedCopyException.Reason.BUSY, RetainedCopyException.Reason.STALE);
            return false;
        }
    }

    private static RetainedSaveState withTextBaseline(RetainedSaveState snapshot) {
        return new RetainedSaveState(
                snapshot.originalCommit(),
                snapshot.baseCommit(),
                snapshot.baselines(),
                Map.of("one.md", RetainedFileBaseline.saved("2".repeat(40), "已保存的旧正文")),
                snapshot.uncertain(),
                snapshot.pending(),
                snapshot.attempt(),
                snapshot.move(),
                snapshot.lastSave(),
                snapshot.lastImport());
    }

    private static RetainedSaveState withImport(RetainedSaveState snapshot, String message) {
        SelectedFileSaves.State state = SelectedFileSaves.State.restore(snapshot);
        state.lastImport = receipt(message);
        return state.snapshot();
    }

    private static RetainedCopyRecord initial() {
        var state = new SelectedFileSaves.State(BASE);
        var checkpoint = new RetainedCopyRecord.Checkpoint(UUID.randomUUID(), "d".repeat(64), 1, state.snapshot());
        var owner = new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID());
        UUID copy = UUID.randomUUID();
        return new RetainedCopyRecord(
                1,
                owner,
                copy,
                0,
                1,
                "a".repeat(64),
                true,
                null,
                CLOCK.millis() + 60000,
                new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                checkpoint,
                null,
                null,
                RetainedBaselineTestData.reference(owner, copy, BASE, CLOCK.millis() + 60000));
    }

    private static RetainedCopyRecord changed(
            RetainedCopyRecord prior,
            long revision,
            long generation,
            String transport,
            RetainedCopyRecord.Checkpoint checkpoint,
            RetainedCopyRecord.Command command) {
        return new RetainedCopyRecord(
                1,
                prior.owner(),
                prior.copyId(),
                revision,
                generation,
                transport,
                prior.fullRead(),
                prior.publicExport(),
                prior.expiresAt(),
                prior.writer(),
                checkpoint,
                command,
                null,
                prior.originalBaseline());
    }

    private static BridgeReplies.RestoredReceipt receipt(String message) {
        return BridgeReplies.RestoredReceipt.capture(BridgeReplies.failed("TEST", message));
    }

    private static Path recordPath(Path root) throws Exception {
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.toString().endsWith(".record"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static void assertNoPending(Path root) throws Exception {
        try (var files = Files.list(root)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith(".pending-"))
                            .count())
                    .isZero();
        }
    }

    private static Process child(String... args) throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                RetainedStoreProcessProbe.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static void assertWriterChild(String expected, Path root, RetainedCopyRecord record) throws Exception {
        assertChild(
                expected,
                "try-writer",
                root.toString(),
                record.owner().subjectId().toString(),
                record.owner().workspaceId().toString(),
                record.copyId().toString());
    }

    private static void assertChild(String expected, String... args) throws Exception {
        Process process = child(args);
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(process.inputReader().readLine()).isEqualTo(expected);
        } finally {
            process.destroyForcibly();
        }
    }

    private static void assertFailure(Runnable operation, RetainedCopyException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        RetainedCopyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
