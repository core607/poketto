package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NativeSynchronizationTests extends NativePlanFixture {
    @Test
    void aConflictingLocalDirectoryIsNeverTreatedAsConfirmedAbsence() {
        changeRemote("private/secret.md", "remote candidate");
        byte[] before = local.get("private/secret.md");
        doThrow(new SandcrabClient.Rejected(400, "NOT_REGULAR_FILE"))
                .when(files)
                .capture(eq(lease), eq("private/secret.md"), anyLong());
        JsonNode reply = call("sync_prepare", new BridgeArguments.Sync());
        assertThat(reply.path("code").stringValue()).isEqualTo("LOCAL_PATH_TYPE");
        assertThat(reply.path("result").path("path").stringValue()).isEqualTo("private/secret.md");
        assertThat(state.get().nativePlan().files()).isEmpty();
        assertThat(local.get("private/secret.md")).containsExactly(before);
    }

    @Test
    void preparationCapacityFailureReturnsItsRetainedPlanWithoutChangingWorkingFiles() {
        changeRemote("private/secret.md", "remote candidate");
        Map<String, byte[]> before = Map.copyOf(local);
        doThrow(new RetainedCopyException(RetainedCopyException.Reason.LIMIT))
                .when(held)
                .publishPlanFile(any(), any(), any(), any());
        JsonNode reply = call("sync_prepare", new BridgeArguments.Sync());
        assertThat(reply.path("code").stringValue()).isEqualTo("PLAN_CAPACITY");
        assertThat(reply.path("result").path("planId").stringValue())
                .isEqualTo(state.get().nativePlan().id().toString());
        assertThat(local).isEqualTo(before);
        assertThat(state.get().nativePlan().files()).isEmpty();
    }

    @Test
    void prepareUsesTheExistingThreeWayMergeAndKeepsWorkingBytesAndRemoteUntouched() {
        byte[] unsaved = "locally edited secret".getBytes(StandardCharsets.UTF_8);
        local.put("private/secret.md", unsaved);
        String remote = changeRemote("private/secret.md", "remotely edited secret");
        int pushes = repository.pushes();
        JsonNode reply = call("sync_prepare", new BridgeArguments.Sync());
        NativeRepositoryPlan plan = state.get().nativePlan();
        assertThat(reply.path("ok").booleanValue()).isTrue();
        assertThat(plan.prepared()).isTrue();
        assertThat(plan.remoteCommit()).isEqualTo(remote);
        NativeRepositoryPlan.File selected = plan.files().get("private/secret.md");
        assertThat(selected.conflict()).isTrue();
        assertThat(new String(retained.get(selected.candidate().sha256()), StandardCharsets.UTF_8))
                .contains(
                        "<<<<<<< LOCAL",
                        "||||||| BASE",
                        ">>>>>>> REMOTE",
                        "locally edited secret",
                        "remotely edited secret");
        assertThat(local.get("private/secret.md")).containsExactly(unsaved);
        assertThat(retained.get(selected.original().sha256())).containsExactly(unsaved);
        assertThat(local.get(NativePlanIo.destination(plan.id(), "candidate", selected.candidate())))
                .containsExactly(retained.get(selected.candidate().sha256()));
        assertThat(state.get().baseCommit()).isEqualTo(repository.sourceCommit());
        assertThat(repository.pushes()).isEqualTo(pushes);
    }

    @Test
    void partialPreparationResumesTheSameRemoteAndRetainsItsOriginalCaptureAfterALostUploadReply() {
        String preparedRemote = changeRemote("private/secret.md", "first remote candidate");
        loseMaterialization.set(true);
        assertThatThrownBy(() -> call("sync_prepare", new BridgeArguments.Sync()))
                .isInstanceOf(WorkerUnavailableException.class);
        NativeRepositoryPlan before = state.get().nativePlan();
        assertThat(before.files()).hasSize(1);
        String capturedPath = before.paths().getFirst();
        NativeRepositoryPlan.File original = before.files().get(capturedPath);
        local.put(capturedPath, "edited after interrupted preparation".getBytes(StandardCharsets.UTF_8));
        changeRemote("private/secret.md", "newer remote candidate");
        do {
            call("sync_prepare", new BridgeArguments.Sync(), () -> true);
        } while (!state.get().nativePlan().prepared());
        NativeRepositoryPlan after = state.get().nativePlan();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.remoteCommit()).isEqualTo(preparedRemote);
        assertThat(after.files().get(capturedPath)).isEqualTo(original);
        assertThat(new String(local.get(capturedPath), StandardCharsets.UTF_8))
                .isEqualTo("edited after interrupted preparation");
        assertThat(state.get().baseCommit()).isEqualTo(repository.sourceCommit());
    }

    @Test
    void acceptanceVerifiesExplicitBytesAndAdvancesOnlyTheSelectedRemoteBaselines() {
        changeRemote("private/secret.md", "remote candidate");
        String remote = changeRemote("AGENTS.md", "remote guide");
        call("sync_prepare", new BridgeArguments.Sync());
        NativeRepositoryPlan plan = state.get().nativePlan();
        var wrong = new BridgeArguments.PlanAccept(
                plan.id().toString(), List.of(new BridgeArguments.PlanExpected("private/secret.md", "f".repeat(64))));
        assertThat(call("sync_accept", wrong).path("code").stringValue()).isEqualTo("LOCAL_FILE_CHANGED");
        assertThat(state.get().nativePlan().accepted()).isEmpty();
        local.put("private/secret.md", "user resolved candidate".getBytes(StandardCharsets.UTF_8));
        String accepted = SandcrabProtocol.sha256(local.get("private/secret.md"));
        call(
                "sync_accept",
                new BridgeArguments.PlanAccept(
                        plan.id().toString(),
                        List.of(new BridgeArguments.PlanExpected("private/secret.md", accepted))));
        assertThat(state.get().baselines())
                .containsEntry("private/secret.md", remote)
                .doesNotContainKey("AGENTS.md");
        assertThat(state.get().fileBaselines().get("private/secret.md").source())
                .isEqualTo("remote candidate");
        assertThat(state.get().baseCommit()).isEqualTo(repository.sourceCommit());
        var remaining = new ArrayList<BridgeArguments.PlanExpected>();
        for (String path : plan.paths()) {
            if (!path.equals("private/secret.md")) {
                remaining.add(new BridgeArguments.PlanExpected(path, identity(path)));
            }
        }
        call("sync_accept", new BridgeArguments.PlanAccept(plan.id().toString(), remaining));
        assertThat(state.get().nativePlan().complete()).isTrue();
        assertThat(state.get().baseCommit()).isEqualTo(remote);
        assertThat(new String(local.get("private/secret.md"), StandardCharsets.UTF_8))
                .isEqualTo("user resolved candidate");
        assertThat(state.get().nativePlan().files()).isEqualTo(plan.files());
    }

    @Test
    void privateOriginalsCanBeRecoveredToANewPathAfterStagingWasEdited() {
        byte[] unsaved = "keep this original draft".getBytes(StandardCharsets.UTF_8);
        local.put("private/secret.md", unsaved);
        changeRemote("private/secret.md", "new remote");
        call("sync_prepare", new BridgeArguments.Sync());
        NativeRepositoryPlan plan = state.get().nativePlan();
        NativeRepositoryPlan.File file = plan.files().get("private/secret.md");
        local.put(NativePlanIo.destination(plan.id(), "original", file.original()), new byte[] {7});
        var query = new BridgeArguments.PlanFile(
                plan.id().toString(), "private/secret.md", "original", "private/recovered.md");
        call("plan_file", query);
        assertThat(local.get("private/recovered.md")).containsExactly(unsaved);
        local.put("private/recovered.md", new byte[] {9});
        assertThat(call("plan_file", query).path("code").stringValue()).isEqualTo("INVALID_SELECTION");
        assertThat(local.get("private/recovered.md")).containsExactly(9);
        assertThat(retained.get(file.original().sha256())).containsExactly(unsaved);
    }

    @Test
    void releaseKeepsAcceptedBaselinesAndWorkingFilesAndOnlyDropsExplicitlyReleasedBackups() {
        String remote = changeRemote("private/secret.md", "remote candidate");
        call("sync_prepare", new BridgeArguments.Sync());
        NativeRepositoryPlan plan = state.get().nativePlan();
        call(
                "sync_accept",
                new BridgeArguments.PlanAccept(
                        plan.id().toString(),
                        List.of(new BridgeArguments.PlanExpected("private/secret.md", identity("private/secret.md")))));
        Map<String, byte[]> before = Map.copyOf(local);
        call("plan_release", new BridgeArguments.PlanIdentity(plan.id().toString()));
        assertThat(state.get().nativePlan()).isNull();
        assertThat(state.get().baselines()).containsEntry("private/secret.md", remote);
        assertThat(retained).isEmpty();
        assertThat(local).isEqualTo(before);
        assertThat(call("plan_show", new BridgeArguments.PlanPage(plan.id().toString(), 0, 64))
                        .path("code")
                        .stringValue())
                .isEqualTo("INVALID_SELECTION");
    }

    @Test
    void planIdentityCurrentAuthorizationAndPublicScopeProtectEveryOperation() {
        call("sync_prepare", new BridgeArguments.Sync());
        NativeRepositoryPlan plan = state.get().nativePlan();
        assertThat(call(
                                "plan_release",
                                new BridgeArguments.PlanIdentity(
                                        UUID.randomUUID().toString()))
                        .path("code")
                        .stringValue())
                .isEqualTo("INVALID_SELECTION");
        denied.set(true);
        assertThatThrownBy(() ->
                        call("plan_show", new BridgeArguments.PlanPage(plan.id().toString(), 0, 1)))
                .isInstanceOf(AuthException.class);
        denied.set(false);
        when(held.owner()).thenReturn(new AccountCopyRecord.Owner(account, workspace.value(), false));
        assertThat(call("plan_show", new BridgeArguments.PlanPage(plan.id().toString(), 0, 1))
                        .path("code")
                        .stringValue())
                .isEqualTo("READ_ONLY_SCOPE");
        assertThat(state.get().nativePlan()).isEqualTo(plan);
    }

    @Test
    void aPreparedPlanSurvivesStateSerializationAndPagesWithinTheOpaqueReplyBudget() {
        changeRemote("private/secret.md", "retained candidate");
        call("sync_prepare", new BridgeArguments.Sync());
        NativeRepositoryPlan plan = state.get().nativePlan();
        state.set(SandcrabProtocol.read(SandcrabProtocol.write(state.get()), RetainedSaveState.class));
        assertThat(state.get().nativePlan()).isEqualTo(plan);
        JsonNode page = call("plan_show", new BridgeArguments.PlanPage(plan.id().toString(), 0, 64));
        assertThat(SandcrabProtocol.write(page).length).isLessThanOrEqualTo(MediaListing.MAX_PAGE_BYTES);
        assertThat(page.path("result").path("items").size())
                .isEqualTo(plan.paths().size());
    }

    private String identity(String path) {
        byte[] bytes = local.get(path);
        return bytes == null ? "absent" : SandcrabProtocol.sha256(bytes);
    }
}
