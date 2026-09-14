package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSyncInputsTests {
    @TempDir
    Path root;

    @Test
    void comparesOneRemoteRevisionAgainstAcknowledgedFilesAndOriginalPaths() throws Exception {
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        var workspace = WorkspaceId.random();
        when(auth.authorize(any(), any()))
                .thenReturn(
                        new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        try (var fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace)) {
            var saves = new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth));
            var local = new SelectedFileSaves.State(fixture.sourceCommit(), state -> {});
            assertThat(saves.save(actor, workspace, local, Map.of("private/secret.md", "acknowledged"), List.of())
                            .ok())
                    .isTrue();
            String acknowledged = local.baseCommit;
            var remote = new SelectedFileSaves.State(acknowledged);
            assertThat(saves.save(
                                    actor,
                                    workspace,
                                    remote,
                                    Map.of("private/新增.md", "new remote file"),
                                    List.of("public/article.md"))
                            .ok())
                    .isTrue();
            String selected = fixture.seedFile("private/data.bin", new byte[] {(byte) 0xff, 0});

            WorkspaceSyncInputs inputs = saves.prepareWorkspaceSync(actor, workspace, local);

            assertThat(inputs.previousCommit()).isEqualTo(acknowledged);
            assertThat(inputs.remoteCommit()).isEqualTo(selected);
            assertThat(inputs.baseline().get("private/secret.md").source()).contains("acknowledged");
            assertThat(inputs.baseline()).containsKey("public/article.md").doesNotContainKey("private/新增.md");
            assertThat(inputs.remote()).doesNotContainKey("public/article.md");
            assertThat(inputs.remote().get("private/新增.md").source()).contains("new remote file");
            assertThat(inputs.remote().get("private/data.bin").expectedAbsence())
                    .isFalse();
            assertThat(inputs.remote().get("private/data.bin").revision()).isPresent();
            assertThat(inputs.paths()).contains("public/article.md", "private/新增.md", "private/data.bin");
            fixture.seedFile("private/新增.md", "later remote version".getBytes(StandardCharsets.UTF_8));
            assertThat(inputs.remote().get("private/新增.md").source()).contains("new remote file");
            assertThat(local.baseCommit).isEqualTo(acknowledged);
        }
    }
}
