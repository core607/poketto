package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.JsonNode;

class NativeMovesTests extends NativePlanFixture {
    @Test
    void preparationRetainsDomainLinkRepairAndNeverChangesRemoteOrWorkingFiles() {
        resetBaseline(changeRemote("private/reference.md", "[Saved note](secret.md)\n"));
        Map<String, byte[]> before = Map.copyOf(local);
        int pushes = repository.pushes();
        JsonNode reply = prepare();
        NativeRepositoryPlan plan = state.get().nativePlan();
        assertThat(reply.path("ok").booleanValue()).isTrue();
        assertThat(plan.prepared()).isTrue();
        assertThat(plan.paths()).containsExactly("private/moved.md", "private/reference.md", "private/secret.md");
        before.forEach((path, bytes) -> assertThat(local.get(path)).containsExactly(bytes));
        assertThat(repository.pushes()).isEqualTo(pushes);
        NativeRepositoryPlan.File reference = plan.files().get("private/reference.md");
        assertThat(new String(retained.get(reference.candidate().sha256()), StandardCharsets.UTF_8))
                .contains("(moved.md)");
        assertThat(retained.get(reference.original().sha256())).containsExactly(before.get("private/reference.md"));
        assertThat(plan.moveState().definition()).isNotNull();
        assertThat(plan.moveState().started()).isFalse();
    }

    @Test
    void finalAcceptanceUsesSavedVersionsAndLeavesEditsMadeAfterAnEarlierAcceptanceUnsaved() {
        prepare();
        applyCandidates();
        NativeRepositoryPlan plan = state.get().nativePlan();
        JsonNode first = accept(List.of("private/moved.md"));
        assertThat(first.path("code").stringValue()).isEqualTo("MOVE_ACCEPTANCE_PENDING");
        assertThat(state.get().nativePlan().accepted()).containsOnlyKeys("private/moved.md");
        assertThat(repository.pushes()).isZero();
        byte[] later = "edited after accepting this path".getBytes(StandardCharsets.UTF_8);
        local.put("private/moved.md", later);
        repository.afterSuccessfulPush(commit -> {
            assertThat(state.get().nativePlan().moveState().attempt().commit()).isEqualTo(commit);
            assertThat(state.get().nativePlan().localComplete()).isTrue();
        });
        JsonNode completed = accept(List.of("private/secret.md"));
        assertThat(completed.path("ok").booleanValue()).isTrue();
        assertThat(completed.at("/result/remoteCommitted").booleanValue()).isTrue();
        assertThat(completed.at("/result/localVerified").booleanValue()).isTrue();
        assertThat(completed.at("/result/verifiedPaths").intValue()).isEqualTo(2);
        assertThat(completed.at("/result/verificationScope").stringValue())
                .contains("own explicit acceptance", "not a simultaneous");
        assertThat(remote("private/moved.md")).isEqualTo("current-secret-needle");
        assertThat(local.get("private/moved.md")).containsExactly(later);
        assertThat(state.get().fileBaselines().get("private/moved.md").source()).isEqualTo("current-secret-needle");
        assertThat(state.get().baseCommit())
                .isEqualTo(state.get().nativePlan().moveState().result().commit());
        assertThat(state.get().nativePlan().id()).isEqualTo(plan.id());
    }

    @Test
    void dirtySourcesAndOccupiedDestinationsRemainUntouchedAndCannotBeAccepted() {
        byte[] dirty = "unsaved original".getBytes(StandardCharsets.UTF_8);
        local.put("private/secret.md", dirty);
        assertThat(prepare().path("code").stringValue()).isEqualTo("LOCAL_MOVE_DIRTY");
        assertThat(local.get("private/secret.md")).containsExactly(dirty);
        assertThat(state.get().nativePlan().prepared()).isFalse();
        assertThat(accept(List.of()).path("code").stringValue()).isEqualTo("INVALID_SELECTION");
        release();
        local.put("private/secret.md", "current-secret-needle".getBytes(StandardCharsets.UTF_8));
        local.put("private/moved.md", dirty);
        assertThat(prepare().path("code").stringValue()).isEqualTo("LOCAL_MOVE_TARGET_EXISTS");
        assertThat(local.get("private/moved.md")).containsExactly(dirty);
        assertThat(repository.pushes()).isZero();
    }

    @Test
    void wrongLocalExpectationCannotAdvanceAcceptanceOrCommit() {
        prepare();
        applyCandidates();
        local.put("private/moved.md", "changed after applying candidate".getBytes(StandardCharsets.UTF_8));
        assertThat(accept(state.get().nativePlan().paths()).path("code").stringValue())
                .isEqualTo("LOCAL_FILE_CHANGED");
        assertThat(state.get().nativePlan().accepted()).isEmpty();
        assertThat(repository.pushes()).isZero();
    }

    @Test
    void partialBaselineRetentionRecoversWithoutRecheckingLaterEditsOrRepeatingTheMove() {
        prepare();
        applyCandidates();
        var checks = new AtomicInteger();
        JsonNode reply =
                call("move_accept", expectations(state.get().nativePlan().paths()), () -> checks.incrementAndGet() > 1);
        assertThat(reply.path("code").stringValue()).isEqualTo("MOVE_BASELINES_PENDING");
        assertThat(reply.at("/result/remoteCommitted").booleanValue()).isTrue();
        assertThat(state.get().nativePlan().moveState().baselined()).hasSize(1);
        assertThat(release().path("code").stringValue()).isEqualTo("RECOVER_MOVE_FIRST");
        local.put("private/moved.md", "later edit".getBytes(StandardCharsets.UTF_8));
        JsonNode recovered = call("recover", new BridgeArguments.Sync());
        assertThat(recovered.path("ok").booleanValue()).isTrue();
        assertThat(recovered.at("/result/localVerified").booleanValue()).isTrue();
        assertThat(state.get().nativePlan().complete()).isTrue();
        assertThat(repository.pushes()).isEqualTo(1);
        assertThat(release().path("ok").booleanValue()).isTrue();
        assertThat(local.get("private/moved.md")).containsExactly("later edit".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void remoteBaseOrPreparedWriteDigestMismatchPreventsAnyPush() {
        var request = new RepositoryMoveRequest(state.get().baseCommit(), "private/secret.md", "private/moved.md");
        RepositoryMovePlan prepared = repository.moves(auth).plan(actor, workspace, request);
        var checkpoints = new AtomicInteger();
        assertThatThrownBy(() -> repository
                        .moves(auth)
                        .movePrepared(
                                actor, workspace, request, "0".repeat(64), attempt -> checkpoints.incrementAndGet()))
                .isInstanceOf(RepositoryConflictException.class);
        assertThat(checkpoints).hasValue(0);
        assertThat(repository.pushes()).isZero();
        assertThat(prepared.writeSha256()).isNotEqualTo("0".repeat(64));
        prepare();
        applyCandidates();
        changeRemote("AGENTS.md", "another remote writer");
        assertThat(accept(state.get().nativePlan().paths()).path("code").stringValue())
                .isEqualTo("REPOSITORY_CONFLICT");
        assertThat(state.get().nativePlan().moveState().attempt()).isNull();
        assertThat(repository.pushes()).isEqualTo(1);
        assertThat(remote("private/secret.md")).isEqualTo("current-secret-needle");
        assertThat(release().path("ok").booleanValue()).isTrue();
    }

    @Test
    void currentPublishAuthorityIsCheckedAgainAfterPreparation() {
        assertThat(call("move_prepare", new BridgeArguments.Move("private/secret.md", "public/moved.md"))
                        .path("ok")
                        .booleanValue())
                .isTrue();
        applyCandidates();
        capabilities.remove(Capability.PUBLISH);
        assertThat(accept(state.get().nativePlan().paths()).path("code").stringValue())
                .isEqualTo("ACCESS_DENIED");
        assertThat(repository.pushes()).isZero();
        assertThat(release().path("code").stringValue()).isEqualTo("RECOVER_MOVE_FIRST");
        assertThat(call("recover", new BridgeArguments.Sync()).path("code").stringValue())
                .isEqualTo("MOVE_NOT_SUBMITTED");
        assertThat(state.get().nativePlan().accepted())
                .hasSize(state.get().nativePlan().paths().size());
        assertThat(release().path("ok").booleanValue()).isTrue();
    }

    @Test
    void revocationBetweenDurableIntentAndSubmissionPreservesRecoveryData() {
        prepare();
        applyCandidates();
        doAnswer(call -> {
                    RetainedSaveState snapshot = state.get();
                    if (snapshot.nativePlan().moveState().started()) {
                        throw new AuthException(AuthException.Code.DENIED);
                    }
                    authorize();
                    return null;
                })
                .when(lease)
                .authorize();
        assertThatThrownBy(() -> accept(state.get().nativePlan().paths())).isInstanceOf(AuthException.class);
        assertThat(repository.pushes()).isZero();
        assertThat(state.get().nativePlan().moveState().recoveryRequired()).isTrue();
        assertThat(state.get().nativePlan().moveState().attempt()).isNull();
        assertThat(retained)
                .containsKey(state.get()
                        .nativePlan()
                        .files()
                        .get("private/secret.md")
                        .original()
                        .sha256());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void mediaMovePreservesUnsavedIndexEntriesLocallyButDoesNotCommitThem() {
        resetBaseline(repository.seedMedia(auth, actor, new byte[] {1}, new byte[] {2}));
        var entries = new LinkedHashMap<>(
                RepositoryMediaIndex.parse(local.get(RepositoryMediaIndex.PATH)).files());
        var draft = new RepositoryMediaIndex.Media(UUID.randomUUID(), "d".repeat(64), "application/pdf", 3);
        entries.put("private/unsaved.pdf", draft);
        local.put(RepositoryMediaIndex.PATH, new RepositoryMediaIndex(entries).encode());
        JsonNode prepared =
                call("move_prepare", new BridgeArguments.Move("private/manual.pdf", "private/archive/manual.pdf"));
        assertThat(prepared.path("ok").booleanValue()).isTrue();
        applyCandidates();
        assertThat(RepositoryMediaIndex.parse(local.get(RepositoryMediaIndex.PATH))
                        .files())
                .containsEntry("private/unsaved.pdf", draft)
                .containsKey("private/archive/manual.pdf");
        assertThat(accept(state.get().nativePlan().paths()).path("ok").booleanValue())
                .isTrue();
        assertThat(RepositoryMediaIndex.parse(remote(RepositoryMediaIndex.PATH).getBytes(StandardCharsets.UTF_8))
                        .files())
                .containsKey("private/archive/manual.pdf")
                .doesNotContainKeys("private/manual.pdf", "private/unsaved.pdf");
    }

    JsonNode prepare() {
        return call("move_prepare", new BridgeArguments.Move("private/secret.md", "private/moved.md"));
    }

    void applyCandidates() {
        state.get().nativePlan().files().forEach((path, file) -> {
            if (file.candidate() == null) {
                local.remove(path);
            } else {
                local.put(path, retained.get(file.candidate().sha256()).clone());
            }
        });
    }

    JsonNode accept(List<String> paths) {
        return call("move_accept", expectations(paths));
    }

    BridgeArguments.PlanAccept expectations(List<String> paths) {
        NativeRepositoryPlan plan = state.get().nativePlan();
        return new BridgeArguments.PlanAccept(
                plan.id().toString(),
                paths.stream()
                        .map(path -> new BridgeArguments.PlanExpected(
                                path, plan.files().get(path).candidateIdentity()))
                        .toList());
    }

    JsonNode release() {
        return call(
                "plan_release",
                new BridgeArguments.PlanIdentity(state.get().nativePlan().id().toString()));
    }

    String remote(String path) {
        return repository
                .reader(auth)
                .getFile(actor, workspace, Optional.empty(), path)
                .source()
                .orElse(null);
    }
}
