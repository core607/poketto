package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledOnOs(OS.LINUX)
class RetainedCommandTests {
    private static final String BASE = "1".repeat(40);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private final RetainedCopyRecord.Owner owner = new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID());
    private final UUID copy = UUID.randomUUID();
    private final RetainedCopyRecord.Writer lease =
            new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @TempDir
    Path directory;

    @Test
    void capturesAnOriginalBeforeCheckpointAndKeepsItsReferenceAcrossGenerationTransfer() throws Exception {
        var store = store();
        var originals = RetainedBaselineTestData.stores(store, directory.resolve("originals"))
                .originals();
        var port = new Checkpoints(store);
        var state = new SelectedFileSaves.State(BASE);
        var file = new RepositoryFile(
                new WorkspaceId(owner.workspaceId()),
                Optional.of(BASE),
                "原文.md",
                false,
                Optional.of("原始内容"),
                Optional.of(DocumentRevision.sha256("原始内容".getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
        RetainedBaseline.Reference reference;
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            command.initialize("a".repeat(64), true, null, lease, state.snapshot(), originals, sink -> {
                assertThat(port.running).isEmpty();
                sink.accept(file);
            });
            reference = command.record().originalBaseline();
            assertThat(reference.identity().expiresAt())
                    .isEqualTo(command.record().expiresAt());
            command.begin(UUID.randomUUID());
            command.complete(state.snapshot());
            assertThat(command.record().originalBaseline()).isEqualTo(reference);
        }
        try (var writer = store.writer(owner, copy);
                var reader = originals.open(writer, reference)) {
            assertThat(reader.find("原文.md")).contains(file);
        }
        try (var resumed = RetainedCommand.resume(store, owner, copy, 1, port)) {
            resumed.transferAfterContainment("f".repeat(64), lease);
            resumed.initialize("f".repeat(64), true, null, lease, state.snapshot(), originals, sink -> {
                throw new AssertionError("recovery must not recapture current remote content");
            });
            assertThat(resumed.record().originalBaseline()).isEqualTo(reference);
        }
    }

    @Test
    void failedOriginalAndInitialCheckpointNeverPublishMetadataAndOrphansAreReclaimed() {
        var store = store();
        var originals = RetainedBaselineTestData.stores(store, directory.resolve("originals"))
                .originals();
        var port = new Checkpoints(store);
        var state = new SelectedFileSaves.State(BASE).snapshot();
        var denial = new AuthException(AuthException.Code.DENIED);
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            assertThatThrownBy(() -> command.initialize("a".repeat(64), true, null, lease, state, originals, sink -> {
                        throw denial;
                    }))
                    .isSameAs(denial);
            assertThat(port.running).isEmpty();
            assertThat(command.record()).isNull();
            port.failReady = true;
            assertThatThrownBy(
                            () -> command.initialize("a".repeat(64), true, null, lease, state, originals, sink -> {}))
                    .isInstanceOf(RetainedCopyException.class);
            assertThat(command.record()).isNull();
            assertThat(originals.collectUnused()).isZero();
        }
        assertThatThrownBy(() -> store.read(owner, copy))
                .isInstanceOfSatisfying(
                        RetainedCopyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.MISSING));
        assertThat(originals.collectUnused()).isEqualTo(1);
    }

    @Test
    void retainsIntentAndIntermediateStateBeforeReplacingTheAcknowledgedPoint() throws Exception {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            SelectedFileSaves.State state = initialize(command);
            UUID first = command.record().acknowledged().id();
            UUID execution = UUID.randomUUID();
            command.begin(execution);
            assertThat(store.read(owner, copy).command().id()).isEqualTo(execution);
            assertThatThrownBy(() -> store.writer(owner, copy)).isInstanceOf(RetainedCopyException.class);

            state.acknowledgeImport(receipt());
            RetainedCopyRecord during = store.read(owner, copy);
            assertThat(during.acknowledged().id()).isEqualTo(first);
            assertThat(importJson(during.command().checkpoint().state())
                            .path("originalStored")
                            .asBoolean())
                    .isTrue();
            assertThat(importJson(during.acknowledged().state())
                            .path("originalStored")
                            .asBoolean())
                    .isFalse();
            assertThat(port.removed).isEmpty();

            command.complete(state.snapshot());
            RetainedCopyRecord complete = store.read(owner, copy);
            assertThat(complete.command()).isNull();
            assertThat(complete.acknowledged().id()).isNotEqualTo(first);
            assertThat(importJson(complete.acknowledged().state())
                            .path("originalStored")
                            .asBoolean())
                    .isTrue();
            assertThat(port.running).containsExactly(Optional.empty(), Optional.of(execution), Optional.empty());
            assertThat(port.removed)
                    .containsExactly(first, during.command().checkpoint().id());
        }
        try (var next = store.writer(owner, copy)) {
            assertThat(next).isNotNull();
        }
    }

    @Test
    void failedActiveCaptureCannotAdvanceLiveStateOrBecomeAnArgumentFailure() {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            SelectedFileSaves.State state = initialize(command);
            command.begin(UUID.randomUUID());
            RetainedCopyRecord before = store.read(owner, copy);
            port.failActive = true;

            assertThatThrownBy(() -> state.acknowledgeImport(receipt()))
                    .isInstanceOf(RetainedCopyException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);

            assertThat(store.read(owner, copy).revision()).isEqualTo(before.revision());
            assertThat(importJson(state.snapshot()).path("originalStored").asBoolean())
                    .isFalse();
            assertThat(port.removed).isEmpty();
        }
    }

    @Test
    void metadataLimitPreservesThePreviousPointAndDoesNotDeleteTheUnconfirmedCapture() {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            SelectedFileSaves.State state = initialize(command);
            command.begin(UUID.randomUUID());
            RetainedCopyRecord before = store.read(owner, copy);
            SelectedFileSaves.State proposed = state.copy();
            proposed.lastSave = BridgeReplies.failed("TEST", "x".repeat(6000));

            assertThatThrownBy(() -> state.install(proposed)).isInstanceOf(RetainedCopyException.class);

            assertThat(store.read(owner, copy).revision()).isEqualTo(before.revision());
            assertThat(port.running).hasSize(2);
            assertThat(port.removed).isEmpty();
            assertThat(state.lastSave).isInstanceOf(BridgeReplies.RestoredReceipt.class);
            assertThat(JSON.writeValueAsString(state.lastSave)).doesNotContain("xxxx");
        }
    }

    @Test
    void failedCompletionKeepsTheIntermediateHostWriteAndLastAcknowledgedPoint() {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            SelectedFileSaves.State state = initialize(command);
            command.begin(UUID.randomUUID());
            state.acknowledgeImport(receipt());
            RetainedCopyRecord before = store.read(owner, copy);
            port.failReady = true;

            assertThatThrownBy(() -> command.complete(state.snapshot())).isInstanceOf(RetainedCopyException.class);

            RetainedCopyRecord after = store.read(owner, copy);
            assertThat(after.revision()).isEqualTo(before.revision());
            assertThat(after.command().checkpoint().id())
                    .isEqualTo(before.command().checkpoint().id());
            assertThat(after.acknowledged().id())
                    .isEqualTo(before.acknowledged().id());
            assertThat(port.removed).isEmpty();
        }
    }

    @Test
    void cleanupFailureDoesNotRevokeAnAcknowledgedCommand() {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            SelectedFileSaves.State state = initialize(command);
            UUID first = command.record().acknowledged().id();
            command.begin(UUID.randomUUID());
            port.failRemove = true;

            command.complete(state.snapshot());

            RetainedCopyRecord after = store.read(owner, copy);
            assertThat(after.command()).isNull();
            assertThat(after.acknowledged().id()).isNotEqualTo(first);
        }
    }

    @Test
    void interruptedCommandRequiresRecoveryAndFailedAdmissionReleasesItsLock() throws Exception {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        RetainedCopyRecord expected;
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            initialize(command);
            command.begin(UUID.randomUUID());
            expected = command.record();
        }

        assertThatThrownBy(() -> new RetainedCommand(store, owner, copy, expected, port))
                .isInstanceOf(RetainedCopyException.class)
                .hasMessageContaining("UNCERTAIN");
        try (var next = store.writer(owner, copy)) {
            assertThat(next).isNotNull();
        }
        assertThat(port.running).hasSize(1);
    }

    @Test
    void recoveryTransfersTheInterruptedPointAndKeepsThePreviousAcknowledgementUntilCompletion() {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        RetainedCopyRecord before;
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            SelectedFileSaves.State state = initialize(command);
            command.begin(UUID.randomUUID());
            state.acknowledgeImport(receipt());
            before = command.record();
        }
        var nextLease = new RetainedCopyRecord.Writer(lease.workerBootId(), UUID.randomUUID(), UUID.randomUUID());
        try (var resumed = RetainedCommand.resume(store, owner, copy, 1, port)) {
            resumed.transferAfterContainment("c".repeat(64), nextLease);
            RetainedCopyRecord transferred = store.read(owner, copy);
            assertThat(transferred.generation()).isEqualTo(2);
            assertThat(transferred.writer()).isEqualTo(nextLease);
            assertThat(transferred.acknowledged()).isEqualTo(before.acknowledged());
            assertThat(JSON.writeValueAsString(resumed.resumedPoint()))
                    .isEqualTo(JSON.writeValueAsString(before.command().checkpoint()));
            assertThat(transferred.command().outcome()).isEqualTo(RetainedCopyRecord.Outcome.INTERRUPTED);
            assertThat(resumed.view().lastInterruptedCommand())
                    .isEqualTo(before.command().id());
            assertThat(port.removed).isEmpty();
            resumed.begin(UUID.randomUUID());
            assertThat(JSON.writeValueAsString(store.read(owner, copy).command().checkpoint()))
                    .isEqualTo(JSON.writeValueAsString(before.command().checkpoint()));
            resumed.complete(resumed.resumedPoint().state());
            assertThat(store.read(owner, copy).command()).isNull();
            assertThat(importJson(store.read(owner, copy).acknowledged().state())
                            .path("originalStored")
                            .asBoolean())
                    .isTrue();
            assertThat(port.removed)
                    .containsExactly(
                            before.acknowledged().id(),
                            before.command().checkpoint().id());
        }
        assertThatThrownBy(() -> RetainedCommand.resume(store, owner, copy, 1, port))
                .isInstanceOfSatisfying(
                        ExecutionAdmissionException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ExecutionAdmissionException.Reason.GENERATION_MISMATCH));
        try (var current = RetainedCommand.resume(store, owner, copy, 2, port)) {
            assertThat(current.record().lastInterruptedCommand())
                    .isEqualTo(before.command().id());
        }
    }

    @Test
    void invalidTransferKeepsTheOldWriterAndDoesNotExposeAResumedPoint() {
        RetainedCopyStore store = store();
        var port = new Checkpoints(store);
        RetainedCopyRecord before;
        try (var command = new RetainedCommand(store, owner, copy, null, port)) {
            initialize(command);
            before = command.record();
        }
        try (var resumed = RetainedCommand.resume(store, owner, copy, 1, port)) {
            assertThatThrownBy(() -> resumed.transferAfterContainment("invalid", lease))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.read(owner, copy)).isEqualTo(before);
            assertThat(resumed.view().resumed()).isFalse();
            assertThatThrownBy(resumed::resumedPoint).isInstanceOf(NullPointerException.class);
            assertThat(port.removed).isEmpty();
        }
    }

    private SelectedFileSaves.State initialize(RetainedCommand command) {
        var state = new SelectedFileSaves.State(BASE);
        command.initialize(
                "a".repeat(64),
                true,
                null,
                lease,
                state.snapshot(),
                RetainedBaselineTestData.stores(store(), directory.resolve("originals"))
                        .originals(),
                sink -> {});
        return SelectedFileSaves.State.restore(state.snapshot(), command::retain);
    }

    private RetainedCopyStore store() {
        return new RetainedCopyStore(
                directory.resolve("retained"),
                new RetainedCopyStore.Limits(8, 4096, 65536, 0, Duration.ofHours(1)),
                CLOCK);
    }

    private static BridgeReplies.ImportReceipt receipt() {
        return new BridgeReplies.ImportReceipt(
                "private/image.png", UUID.randomUUID().toString(), "f".repeat(64), "image/png", 42, true, false, false);
    }

    private static JsonNode importJson(RetainedSaveState state) {
        return JSON.valueToTree(state.lastImport());
    }

    private final class Checkpoints implements RetainedCommand.Port {
        private final RetainedCopyStore store;
        private final List<Optional<UUID>> running = new ArrayList<>();
        private final List<UUID> removed = new ArrayList<>();
        private boolean failActive;
        private boolean failReady;
        private boolean failRemove;

        private Checkpoints(RetainedCopyStore store) {
            this.store = store;
        }

        @Override
        public WorkerResponses.CheckpointReply capture(UUID id, long expiresAt, Optional<UUID> execution) {
            running.add(execution);
            if ((execution.isPresent() && failActive) || (execution.isEmpty() && failReady)) {
                throw new IllegalArgumentException("injected worker checkpoint refusal");
            }
            return new WorkerResponses.CheckpointReply(
                    true,
                    lease.leaseId().toString(),
                    BASE,
                    execution.isPresent() ? "RUNNING" : "READY",
                    execution.map(UUID::toString).orElse(null),
                    new WorkerResponses.CheckpointDescriptor(id.toString(), "b".repeat(64), 4096, expiresAt));
        }

        @Override
        public void remove(RetainedCopyRecord.Checkpoint checkpoint) {
            RetainedCopyRecord current = store.read(owner, copy);
            assertThat(current.acknowledged().id()).isNotEqualTo(checkpoint.id());
            if (current.command() != null) {
                assertThat(current.command().checkpoint().id()).isNotEqualTo(checkpoint.id());
            }
            if (failRemove) {
                throw new WorkerUnavailableException();
            }
            removed.add(checkpoint.id());
        }
    }
}
