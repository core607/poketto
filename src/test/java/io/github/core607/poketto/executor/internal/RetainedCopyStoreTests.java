package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositoryMoveRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    void reopensExactCheckpointAndUnconfirmedCommandWithoutMutableAliases() throws Exception {
        Path root = directory.resolve("retained");
        var first = new RetainedCopyStore(root, LIMITS, CLOCK);
        var initial = initial();
        first.create(initial);
        var live = SelectedFileSaves.State.restore(initial.acknowledged().state());
        live.acknowledgeMove("2".repeat(40), Set.of("one.md"));
        var pending = new RetainedCopyRecord.Command(
                UUID.randomUUID(), RetainedCopyRecord.Outcome.RUNNING, live.snapshot(), receipt("unconfirmed command"));
        var next = changed(initial, 1, 1, initial.transportHash(), initial.acknowledged(), pending);
        first.replace(0, 1, next);

        var loaded = new RetainedCopyStore(root, LIMITS, CLOCK).read(initial.owner(), initial.copyId());

        assertThat(loaded.revision()).isEqualTo(1);
        assertThat(loaded.command().id()).isEqualTo(pending.id());
        assertThat(loaded.command().outcome()).isEqualTo(RetainedCopyRecord.Outcome.RUNNING);
        assertThat(loaded.acknowledged().state().baseCommit()).isEqualTo(BASE);
        assertThat(loaded.command().state().baseCommit()).isEqualTo("2".repeat(40));
        assertThat(loaded.command().state().baselines()).containsEntry("one.md", "2".repeat(40));
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
                UUID.randomUUID(), "d".repeat(64), 10, initial.acknowledged().state(), receipt("x".repeat(6000)));
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
                UUID.randomUUID(), "d".repeat(64), 10, initial.acknowledged().state(), receipt("x".repeat(3000)));
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
                UUID.randomUUID(), RetainedCopyRecord.Outcome.INTERRUPTED, state.snapshot(), receipt("pending move"));
        var record = changed(initial, 0, 1, initial.transportHash(), initial.acknowledged(), command);
        new RetainedCopyStore(root, limits, CLOCK).create(record);

        var restored = new RetainedCopyStore(root, limits, CLOCK).read(initial.owner(), initial.copyId());

        byte[] restoredPayload = restored.command().state().move().payload();
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

    private static RetainedCopyRecord initial() {
        var state = new SelectedFileSaves.State(BASE);
        var checkpoint = new RetainedCopyRecord.Checkpoint(
                UUID.randomUUID(),
                "d".repeat(64),
                1,
                state.snapshot(),
                BridgeReplies.RestoredReceipt.capture(new BridgeReplies.Absent()));
        return new RetainedCopyRecord(
                1,
                new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(),
                0,
                1,
                "a".repeat(64),
                true,
                CLOCK.millis() + 60000,
                checkpoint,
                null);
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
                prior.expiresAt(),
                checkpoint,
                command);
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

    private static void assertFailure(Runnable operation, RetainedCopyException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        RetainedCopyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
