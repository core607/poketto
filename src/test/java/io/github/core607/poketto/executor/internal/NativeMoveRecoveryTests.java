package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NativeMoveRecoveryTests extends NativePlanFixture {
    @Override
    boolean loseFirstReply() {
        return true;
    }

    @Test
    void lostRemoteAcknowledgementRetainsOneCandidateAndRecoveryNeverReplaysIt() {
        call("move_prepare", new BridgeArguments.Move("private/secret.md", "private/moved.md"));
        NativeRepositoryPlan plan = state.get().nativePlan();
        local.remove("private/secret.md");
        local.put(
                "private/moved.md",
                retained.get(plan.files().get("private/moved.md").candidate().sha256()));
        var accepted = new BridgeArguments.PlanAccept(
                plan.id().toString(),
                plan.paths().stream()
                        .map(path -> new BridgeArguments.PlanExpected(
                                path, plan.files().get(path).candidateIdentity()))
                        .toList());
        JsonNode unknown = call("move_accept", accepted);
        assertThat(unknown.path("code").stringValue()).isEqualTo("WRITE_OUTCOME_UNKNOWN");
        NativeMoveState intent = state.get().nativePlan().moveState();
        assertThat(intent.attempt()).isNotNull();
        assertThat(intent.result()).isNull();
        assertThat(call("status", new BridgeArguments.Status(false))
                        .at("/result/writeOutcomeUnknown")
                        .booleanValue())
                .isTrue();
        assertThat(call("move_accept", accepted).path("code").stringValue()).isEqualTo("RECOVER_MOVE_FIRST");
        assertThat(call(
                                "plan_release",
                                new BridgeArguments.PlanIdentity(plan.id().toString()))
                        .path("code")
                        .stringValue())
                .isEqualTo("RECOVER_MOVE_FIRST");
        byte[] serialized = SandcrabProtocol.write(state.get());
        state.set(SandcrabProtocol.read(serialized, RetainedSaveState.class));
        repository.restoreTransport();
        JsonNode recovered =
                call("move_recover", new BridgeArguments.PlanIdentity(plan.id().toString()));
        assertThat(recovered.path("ok").booleanValue()).isTrue();
        assertThat(recovered.at("/result/remoteCommitted").booleanValue()).isTrue();
        assertThat(recovered.at("/result/localVerified").booleanValue()).isTrue();
        assertThat(state.get().nativePlan().moveState().attempt().object())
                .containsExactly(intent.attempt().object());
        assertThat(state.get().baseCommit()).isEqualTo(intent.attempt().commit());
        assertThat(repository.pushes()).isEqualTo(1);
        assertThat(call("recover", new BridgeArguments.Sync()).path("ok").booleanValue())
                .isTrue();
        assertThat(repository.pushes()).isEqualTo(1);
        assertThat(state.get().nativePlan().accepted().keySet())
                .containsAll(List.of("private/secret.md", "private/moved.md"));
    }
}
