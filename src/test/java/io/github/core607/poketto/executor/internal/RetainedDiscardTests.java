package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RetainedDiscardTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private static final RetainedCopyStore.Limits LIMITS =
            new RetainedCopyStore.Limits(8, 4096, 32768, 0, Duration.ofHours(1));

    @TempDir
    Path directory;

    @Test
    void unresolvedOrFailedContainmentCannotDeleteAcknowledgedWork() {
        var store = store(CLOCK);
        var record = initial();
        store.create(record);
        try (var discard = new RetainedDiscard(store, record.owner(), record.copyId(), 1)) {
            assertThatThrownBy(() -> discard.removeAfterContainment(new CompletableFuture<>()))
                    .isInstanceOf(RetainedCopyException.class)
                    .hasMessageContaining("UNCERTAIN");
            assertThatThrownBy(() -> discard.removeAfterContainment(
                            CompletableFuture.failedFuture(new IllegalStateException("close failed"))))
                    .isInstanceOf(RetainedCopyException.class)
                    .hasMessageContaining("UNCERTAIN");
            assertThat(store.read(record.owner(), record.copyId())).isEqualTo(record);
            assertThatThrownBy(() -> store.writer(record.owner(), record.copyId()))
                    .isInstanceOf(RetainedCopyException.class)
                    .hasMessageContaining("BUSY");
            discard.removeAfterContainment(CompletableFuture.completedFuture(null));
        }
        try (var retry = new RetainedDiscard(store, record.owner(), record.copyId(), 1)) {
            assertThat(retry.record()).isNull();
        }
    }

    @Test
    void staleGenerationRefusesDeletionAndReleasesItsWriter() throws Exception {
        var store = store(CLOCK);
        var record = initial();
        store.create(record);
        assertThatThrownBy(() -> new RetainedDiscard(store, record.owner(), record.copyId(), 2))
                .isInstanceOfSatisfying(
                        ExecutionAdmissionException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ExecutionAdmissionException.Reason.GENERATION_MISMATCH));
        try (var writer = store.writer(record.owner(), record.copyId())) {
            writer.requireValid();
            assertThat(store.read(record.owner(), record.copyId())).isEqualTo(record);
        }
    }

    @Test
    void expiredOwnerCanDiscardButCannotReadForRecovery() {
        var store = store(CLOCK);
        var record = initial();
        store.create(record);
        var later = store(Clock.offset(CLOCK, Duration.ofMinutes(2)));
        assertThatThrownBy(() -> later.read(record.owner(), record.copyId()))
                .isInstanceOf(RetainedCopyException.class)
                .hasMessageContaining("EXPIRED");
        try (var discard = new RetainedDiscard(later, record.owner(), record.copyId(), 1)) {
            assertThat(discard.record()).isEqualTo(record);
            discard.removeAfterContainment(CompletableFuture.completedFuture(null));
        }
    }

    @Test
    void otherSubjectAndWorkspaceCannotAddressTheOwnedRecord() {
        var store = store(CLOCK);
        var record = initial();
        store.create(record);
        var otherSubject =
                new RetainedCopyRecord.Owner(UUID.randomUUID(), record.owner().workspaceId());
        var otherWorkspace = new RetainedCopyRecord.Owner(record.owner().subjectId(), UUID.randomUUID());
        try (var first = new RetainedDiscard(store, otherSubject, record.copyId(), 1);
                var second = new RetainedDiscard(store, otherWorkspace, record.copyId(), 1)) {
            assertThat(first.record()).isNull();
            assertThat(second.record()).isNull();
            assertThat(store.read(record.owner(), record.copyId())).isEqualTo(record);
        }
    }

    @Test
    void expiredReadRequiresTheExactHeldWriter() throws Exception {
        var store = store(CLOCK);
        var record = initial();
        store.create(record);
        try (var wrong = store.writer(record.owner(), UUID.randomUUID())) {
            assertThatThrownBy(() -> store.readForDiscard(wrong, record.owner(), record.copyId()))
                    .isInstanceOf(RetainedCopyException.class);
        }
    }

    private RetainedCopyStore store(Clock clock) {
        return new RetainedCopyStore(directory.resolve("records"), LIMITS, clock);
    }

    private static RetainedCopyRecord initial() {
        String base = "1".repeat(40);
        var state = new SelectedFileSaves.State(base);
        var point = new RetainedCopyRecord.Checkpoint(UUID.randomUUID(), "d".repeat(64), 1, state.snapshot());
        var owner = new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID());
        UUID copy = UUID.randomUUID();
        long expiry = CLOCK.millis() + 60000;
        return new RetainedCopyRecord(
                1,
                owner,
                copy,
                0,
                1,
                "a".repeat(64),
                true,
                null,
                expiry,
                new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                point,
                null,
                null,
                RetainedBaselineTestData.reference(owner, copy, base, expiry));
    }
}
