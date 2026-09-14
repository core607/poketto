package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceSynchronizationTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private PublicExecutionNativeFixture fixture;
    private SelectedFileSaves saves;

    @BeforeEach
    void prepare() throws Exception {
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        saves = new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth));
    }

    @Test
    void lostInstallationAcknowledgementReplaysTheExactMergeAfterJournalReopen() throws Exception {
        String baseline = "first\nmiddle\nlast\n";
        var state = new SelectedFileSaves.State(fixture.sourceCommit(), snapshot -> {});
        assertThat(saves.save(actor, workspace, state, Map.of("AGENTS.md", baseline), List.of())
                        .ok())
                .isTrue();
        String previous = state.baseCommit;
        var competitor = new SelectedFileSaves.State(previous);
        String remote = "first\nmiddle\nremote\n";
        assertThat(saves.save(actor, workspace, competitor, Map.of("AGENTS.md", remote), List.of())
                        .ok())
                .isTrue();
        Path local = Files.createDirectory(root.resolve("local"));
        Files.writeString(local.resolve("AGENTS.md"), "local\nmiddle\nlast\n");
        Files.writeString(local.resolve("draft.md"), "unsaved");
        Path journal = root.resolve("sync.json");
        var mapper = JsonMapper.builder().build();
        var failure = new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        var resumed = SelectedFileSaves.State.restore(state.snapshot(), snapshot -> {
            var pending = snapshot.sync();
            if (pending != null
                    && pending.current() == null
                    && pending.paths().subList(0, pending.next()).contains("AGENTS.md")) {
                throw failure;
            }
            write(journal, mapper.writeValueAsString(snapshot));
        });
        var sync = new WorkspaceSynchronization(saves);
        assertThatThrownBy(() -> sync.run(actor, workspace, resumed, localFiles(local)))
                .isSameAs(failure);
        assertThat(resumed.baseCommit).isEqualTo(previous);
        assertThat(resumed.baseline("AGENTS.md")).isEqualTo(previous);
        assertThat(Files.readString(local.resolve("AGENTS.md"))).isEqualTo("local\nmiddle\nremote\n");
        var stored = mapper.readValue(Files.readAllBytes(journal), RetainedSaveState.class);
        assertThat(stored.sync().current().path()).isEqualTo("AGENTS.md");
        var reopened = SelectedFileSaves.State.restore(
                stored, snapshot -> write(journal, mapper.writeValueAsString(snapshot)));
        assertThat(sync.run(actor, workspace, reopened, localFiles(local)).ok()).isTrue();
        assertThat(reopened.sync).isNull();
        assertThat(reopened.baseCommit).isEqualTo(competitor.baseCommit);
        assertThat(saves.baselineFile(actor, workspace, reopened, "AGENTS.md").source())
                .contains(remote);
        assertThat(Files.readString(local.resolve("AGENTS.md"))).isEqualTo("local\nmiddle\nremote\n");
        assertThat(Files.readString(local.resolve("draft.md"))).isEqualTo("unsaved");
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "AGENTS.md")
                        .source())
                .contains(remote);
    }

    @Test
    void conflictReceiptStaysWithinRetainedReplyBoundsWithoutHidingTheTotal() {
        List<String> paths = java.util.stream.IntStream.range(0, 16384)
                .mapToObj(index -> "private/" + "长".repeat(90) + index + ".md")
                .toList();
        var pending = new PendingWorkspaceSync("a".repeat(40), paths, paths.size(), paths, null);
        var state = new SelectedFileSaves.State("b".repeat(40));
        state.retainSync(pending);
        BridgeReplies.Reply reply = state.finishSync(false);
        var mapper = JsonMapper.builder().build();
        var data = mapper.valueToTree(reply.result());
        assertThat(data.get("conflictCount").asInt()).isEqualTo(16384);
        assertThat(data.get("conflictsTruncated").asBoolean()).isTrue();
        assertThat(mapper.writeValueAsBytes(reply).length).isLessThan(65536);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(state.snapshot()), RetainedSaveState.class)
                        .lastSave())
                .isNotNull();
    }

    private static WorkspaceSynchronization.Files localFiles(Path root) {
        return new WorkspaceSynchronization.Files() {
            @Override
            public WorkspaceSynchronization.Local read(String path) {
                try {
                    Path file = root.resolve(path);
                    String text = Files.exists(file) ? Files.readString(file) : null;
                    return new WorkspaceSynchronization.Local(hash(text), text, false);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public boolean install(PendingWorkspaceSync.File file) {
                var current = read(file.path());
                if (Objects.equals(current.sha256(), hash(file.text()))) {
                    return true;
                }
                if (!Objects.equals(current.sha256(), file.expectedSha256())) {
                    return false;
                }
                write(root.resolve(file.path()), file.text());
                return true;
            }
        };
    }

    private static String hash(String value) {
        return value == null
                ? null
                : DocumentRevision.sha256(value.getBytes(StandardCharsets.UTF_8))
                        .value()
                        .substring(7);
    }

    private static void write(Path path, String value) {
        try {
            if (value == null) {
                Files.deleteIfExists(path);
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, value);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
