package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RetainedSaveStateTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "1".repeat(40);
    private static final String ADVANCED = "2".repeat(40);
    private static final String CANDIDATE = "3".repeat(40);

    @Test
    void roundTripPreservesIndependentBaselinesAndUncertainSave() {
        var state = stateWithIndependentBaselines();
        state.pending = patch(ADVANCED);
        state.attempt = Optional.of(new RepositoryWriteAttempt(CANDIDATE, new byte[] {0, 1, 2, -1}));
        state.uncertain = true;
        state.lastSave = BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN", "Recover the retained attempt");

        var snapshot = roundTrip(state.snapshot());
        var restored = SelectedFileSaves.State.restore(snapshot);

        assertThat(restored.baseCommit).isEqualTo(ADVANCED);
        assertThat(restored.baseline("changed.md")).isEqualTo(ADVANCED);
        assertThat(restored.baseline("untouched.md")).isEqualTo(ORIGINAL);
        assertThat(restored.pending).isEqualTo(state.pending);
        assertThat(restored.uncertain).isTrue();
        assertThat(restored.attempt.orElseThrow().commit()).isEqualTo(CANDIDATE);
        assertThat(restored.attempt.orElseThrow().object()).containsExactly(0, 1, 2, -1);
        assertThat(JSON.<JsonNode>valueToTree(restored.lastSave)).isEqualTo(JSON.valueToTree(state.lastSave));
        state.acknowledgeMove(CANDIDATE, Set.of("untouched.md"));
        assertThat(restored.baseline("untouched.md")).isEqualTo(ORIGINAL);
        assertThat(snapshot.baselines()).containsOnlyKeys("changed.md");
    }

    @Test
    void roundTripPreservesMovePayloadAndConfirmedRemoteOutcomeWithoutAliasing() {
        var state = stateWithIndependentBaselines();
        byte[] payload = {0, -1, 42, 10};
        state.move = new SessionMoves.Pending(
                new RepositoryMoveRequest(ADVANCED, "from.md", "to.md"), payload, Set.of("from.md", "to.md"));
        state.move.attempt = new RepositoryWriteAttempt(CANDIDATE, new byte[] {4, 5, 6});
        state.move.result = new RepositoryPatchResult(CANDIDATE, true, false, Map.of("from.md", Optional.empty()));
        state.lastSave = BridgeReplies.succeeded(new BridgeReplies.MoveSkipped(ADVANCED, true, false, true));
        var snapshot = state.snapshot();
        payload[0] = 99;

        var restored = SelectedFileSaves.State.restore(roundTrip(snapshot));

        assertThat(restored.move.request).isEqualTo(state.move.request);
        assertThat(restored.move.paths).containsExactlyInAnyOrder("from.md", "to.md");
        assertThat(restored.move.payload).containsExactly(0, -1, 42, 10);
        assertThat(restored.move.attempt.object()).containsExactly(4, 5, 6);
        assertThat(restored.move.result).isEqualTo(state.move.result);
        assertThat(restored.baseline("to.md")).isEqualTo(ORIGINAL);
        restored.move.payload[0] = 77;
        assertThat(snapshot.move().payload()).containsExactly(0, -1, 42, 10);
    }

    @Test
    void receiptsRetainTheirExistingJsonShapeForEverySaveAndMoveOutcome() {
        List<BridgeReplies.Recorded> receipts = List.of(
                new BridgeReplies.Absent(),
                BridgeReplies.failed("REPOSITORY_CONFLICT", "Local work retained"),
                BridgeReplies.succeeded(new BridgeReplies.SaveResult(ADVANCED, true, false, List.of("a.md"), true)),
                BridgeReplies.succeeded(new BridgeReplies.MoveInstalled(ADVANCED, true, true, true, "a.md", "b.md")),
                BridgeReplies.succeededWithMessage(
                        new BridgeReplies.MoveSkipped(ADVANCED, true, false, true), "Local files are unchanged"));
        for (var receipt : receipts) {
            var state = stateWithIndependentBaselines();
            state.lastSave = receipt;
            var restored = SelectedFileSaves.State.restore(roundTrip(state.snapshot()));
            assertThat(JSON.<JsonNode>valueToTree(restored.lastSave)).isEqualTo(JSON.valueToTree(receipt));
        }
    }

    @Test
    void maximumValidSavePathListFitsTheReceiptBudget() {
        var paths = new ArrayList<String>();
        for (int index = 0; index < RepositoryPatch.MAX_CHANGES; index++) {
            paths.add(String.format("%02d", index) + "文".repeat(249) + ".md");
        }
        var state = stateWithIndependentBaselines();
        state.lastSave = BridgeReplies.succeeded(new BridgeReplies.SaveResult(ADVANCED, true, true, paths, false));
        var restored = SelectedFileSaves.State.restore(roundTrip(state.snapshot()));
        assertThat(JSON.<JsonNode>valueToTree(restored.lastSave)).isEqualTo(JSON.valueToTree(state.lastSave));
    }

    @Test
    void rejectsInconsistentAuthorityBeforeRestoration() {
        var state = stateWithIndependentBaselines();
        state.pending = patch(ORIGINAL);
        state.uncertain = true;
        assertThatThrownBy(state::snapshot)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base commit");
        state.pending = null;
        assertThatThrownBy(state::snapshot)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncertain");
        state.uncertain = false;
        state.attempt = Optional.of(new RepositoryWriteAttempt(CANDIDATE, new byte[] {1}));
        assertThatThrownBy(state::snapshot)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pending save");
    }

    @Test
    void rejectsUnsafeBaselinePathsAndOversizedReceipts() {
        var receipt = BridgeReplies.RestoredReceipt.capture(new BridgeReplies.Absent());
        assertThatThrownBy(() -> new RetainedSaveState(
                        ORIGINAL, ADVANCED, Map.of("../escape.md", ORIGINAL), false, null, null, null, receipt))
                .isInstanceOf(IllegalArgumentException.class);
        var oversized = JSON.createObjectNode().put("message", "x".repeat(65536));
        assertThatThrownBy(() -> new BridgeReplies.RestoredReceipt(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 KiB");
    }

    private static SelectedFileSaves.State stateWithIndependentBaselines() {
        var state = new SelectedFileSaves.State(ORIGINAL);
        state.acknowledgeMove(ADVANCED, Set.of("changed.md"));
        return state;
    }

    private static RepositoryPatch patch(String base) {
        return new RepositoryPatch(
                Optional.of(base),
                List.of(new RepositoryTextChange("new.md", true, Optional.empty(), Optional.of("未确认的修改"))));
    }

    private static RetainedSaveState roundTrip(RetainedSaveState value) {
        return JSON.readValue(JSON.writeValueAsBytes(value), RetainedSaveState.class);
    }
}
