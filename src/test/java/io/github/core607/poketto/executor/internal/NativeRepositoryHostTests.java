package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NativeRepositoryHostTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AccountCommand held = mock(AccountCommand.class);
    private final NativeRepositoryLease lease = mock(NativeRepositoryLease.class);
    private final List<RetainedSaveState> persisted = new ArrayList<>();
    private SelectedFileSaves.State state;

    private PublicExecutionNativeFixture fixture(boolean loseReply) throws Exception {
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        when(auth.authorize(any(), any(), any(Capability[].class)))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace, loseReply);
        var owner = new AccountCopyRecord.Owner(actor.accountId(), workspace.value(), true);
        when(held.owner()).thenReturn(owner);
        state = new SelectedFileSaves.State(fixture.sourceCommit(), persisted::add);
        when(held.state()).thenReturn(state);
        return fixture;
    }

    private NativeRepositoryHost host(PublicExecutionNativeFixture fixture, NativeRepositoryHost.SnapshotReader files) {
        return new NativeRepositoryHost(
                new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth)),
                files,
                mock(NativeArtifactCommands.class),
                mock(NativeMediaCommands.class),
                mock(NativePlanCommands.class),
                mock(NativeExportCommands.class));
    }

    private JsonNode reply(NativeRepositoryHost host, String request) {
        return JsonMapper.builder()
                .build()
                .readTree(host.reply(actor, held, lease, request.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void selectedSnapshotIsSavedThroughTheRealGitWriterAndKeepsIndependentBaselines() throws Exception {
        try (var fixture = fixture(false)) {
            List<String> captured = new ArrayList<>();
            var host = host(fixture, (session, path, maximum) -> {
                captured.add(path);
                return "native selected save".getBytes(StandardCharsets.UTF_8);
            });
            JsonNode result = reply(host, """
                    {"operation":"save","arguments":{"writes":["private/secret.md"],"deletes":[]}}
                    """);
            assertThat(result.path("ok").booleanValue()).isTrue();
            assertThat(captured).containsExactly("work/repository/private/secret.md");
            assertThat(fixture.pushes()).isEqualTo(1);
            assertThat(fixture.reader(auth)
                            .getFile(actor, workspace, Optional.empty(), "private/secret.md")
                            .source())
                    .contains("native selected save");
            assertThat(state.baseline("AGENTS.md")).isEqualTo(fixture.sourceCommit());
            assertThat(state.baseline("private/secret.md")).isEqualTo(state.baseCommit);
            assertThat(persisted.getFirst().uncertain()).isTrue();
            assertThat(persisted.getLast().uncertain()).isFalse();
        }
    }

    @Test
    void uncertainRemoteSaveRetainsItsCandidateAndExplicitRecoveryDoesNotPushAgain() throws Exception {
        try (var fixture = fixture(true)) {
            var host = host(
                    fixture, (session, path, maximum) -> "retained native change".getBytes(StandardCharsets.UTF_8));
            JsonNode saved = reply(host, """
                    {"operation":"save","arguments":{"writes":["private/secret.md"],"deletes":[]}}
                    """);
            assertThat(saved.path("ok").booleanValue()).isFalse();
            assertThat(state.uncertain).isTrue();
            assertThat(persisted.getLast().attempt()).isNotNull();
            fixture.restoreTransport();
            JsonNode recovered = reply(host, """
                    {"operation":"recover","arguments":{}}
                    """);
            assertThat(recovered.path("ok").booleanValue()).isTrue();
            assertThat(state.uncertain).isFalse();
            assertThat(fixture.pushes()).isEqualTo(1);
        }
    }

    @Test
    void deletionRequiresConfirmedLocalAbsenceAndNeverTreatsADownloadFailureAsAbsence() throws Exception {
        try (var fixture = fixture(false)) {
            var missing = host(fixture, (session, path, maximum) -> {
                throw new SandcrabClient.Rejected(404, "FILE_NOT_FOUND");
            });
            JsonNode deleted = reply(missing, """
                    {"operation":"save","arguments":{"writes":[],"deletes":["private/secret.md"]}}
                    """);
            assertThat(deleted.path("ok").booleanValue()).isTrue();
            var unavailable = host(fixture, (session, path, maximum) -> {
                throw new WorkerUnavailableException();
            });
            assertThatThrownBy(() -> reply(unavailable, """
                    {"operation":"save","arguments":{"writes":[],"deletes":["AGENTS.md"]}}
                    """)).isInstanceOf(WorkerUnavailableException.class);
            assertThat(fixture.pushes()).isEqualTo(1);
        }
    }

    @Test
    void authorizationLostAfterCapturePreventsTheRemoteWrite() throws Exception {
        try (var fixture = fixture(false)) {
            var checks = new AtomicInteger();
            doAnswer(call -> {
                        if (checks.incrementAndGet() >= 3) {
                            throw new AuthException(AuthException.Code.DENIED);
                        }
                        return null;
                    })
                    .when(lease)
                    .authorize();
            var host = host(fixture, (session, path, maximum) -> "do not publish".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> reply(host, """
                    {"operation":"save","arguments":{"writes":["private/secret.md"],"deletes":[]}}
                    """)).isInstanceOf(AuthException.class);
            assertThat(fixture.pushes()).isZero();
            assertThat(persisted).isEmpty();
        }
    }

    @Test
    void missingSelectedWriteIsRejectedWithoutInferringADeletion() throws Exception {
        try (var fixture = fixture(false)) {
            var host = host(fixture, (session, path, maximum) -> {
                throw new SandcrabClient.Rejected(404, "FILE_NOT_FOUND");
            });
            JsonNode result = reply(host, """
                    {"operation":"save","arguments":{"writes":["private/secret.md"],"deletes":[]}}
                    """);
            assertThat(result.path("ok").booleanValue()).isFalse();
            assertThat(result.path("code").stringValue()).isEqualTo("INVALID_SELECTION");
            assertThat(fixture.pushes()).isZero();
            assertThat(persisted).isEmpty();
        }
    }
}
