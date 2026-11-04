package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixtures were encoded by commit 906e5072 after a real synthetic Git save and a lost move response. */
class RetainedLegacyMoveTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "a8926d8e60d1ec30f2ac80647de4288f17af8321";
    private static final String SAVED = "9a26dbcdac56b08647b3c9b89b57835feb3c1c7f";
    private static final String ATTEMPT = "8ea1e6b8828d85ded9a446395e2b9eca0082b940";

    @Test
    void theOldEncoderStateKeepsItsOpaqueMoveAttemptAndIndependentSavedBaseline() throws Exception {
        byte[] encoded = fixture("legacy-move-state.json");
        RetainedSaveState retained = JSON.readValue(encoded, RetainedSaveState.class);
        SelectedFileSaves.State restored = SelectedFileSaves.State.restore(retained);
        assertThat(restored.snapshot().originalCommit()).isEqualTo(ORIGINAL);
        assertThat(restored.baseCommit).isEqualTo(SAVED);
        assertThat(restored.baseline("private/secret.md")).isEqualTo(ORIGINAL);
        assertThat(restored.baseline("private/saved.md")).isEqualTo(SAVED);
        assertThat(restored.snapshot().fileBaselines().get("private/saved.md").source())
                .isEqualTo("saved baseline before pending move\n");
        assertThat(restored.uncertain).isFalse();
        assertThat(restored.move.result).isNull();
        assertThat(restored.move.attempt.commit()).isEqualTo(ATTEMPT);
        assertThat(SandcrabProtocol.sha256(restored.move.payload))
                .isEqualTo("c5e6dbf900e62c165b629b96359cf4cd0259b02d7742f8e42d97971afc7a932b");
        assertThat(SandcrabProtocol.sha256(restored.move.attempt.object()))
                .isEqualTo("47d375ac431879f2bcb3695c1fc6fb58c8803e9d0ba17b52afb20d739571de5f");
        try (var objects = new ObjectInserter.Formatter()) {
            assertThat(objects.idFor(Constants.OBJ_COMMIT, restored.move.attempt.object())
                            .name())
                    .isEqualTo(ATTEMPT);
        }
        assertThat(normalize(SandcrabProtocol.write(restored.snapshot()), "move"))
                .isEqualTo(normalize(encoded, "move"));
    }

    @Test
    void theOldDiskJournalRetainsItsWriterExecutionAndBaselineDigestWithoutExecutingItsPayload() throws Exception {
        byte[] encoded = fixture("legacy-move-journal.json");
        StoredAccountCopy stored = JSON.readValue(encoded, StoredAccountCopy.class);
        byte[] baselineBytes = fixture("legacy-move-baselines.json");
        Map<String, RetainedFileBaseline> baselines = JSON.readValue(baselineBytes, new TypeReference<>() {});
        assertThat(SandcrabProtocol.sha256(baselineBytes))
                .isEqualTo(stored.state().fileBaselines());
        AccountCopyRecord record = stored.restore(baselines);
        assertThat(record.writer().storage()).isNull();
        assertThat(record.writer().principalId()).isEqualTo(UUID.fromString("10000000-0000-0000-0000-000000000002"));
        assertThat(record.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
        assertThat(record.executionId()).isEqualTo(UUID.fromString("10000000-0000-0000-0000-000000000008"));
        assertThat(record.state().move().attempt().commit()).isEqualTo(ATTEMPT);
        var reader = mock(AuthorizedRepositoryReader.class);
        var patches = mock(RepositoryPatchService.class);
        var moves = mock(RepositoryMoveService.class);
        var saves = new SelectedFileSaves(mock(AuthService.class), reader, patches, moves);
        SelectedFileSaves.State state = SelectedFileSaves.State.restore(record.state());
        assertThat(saves.save(
                                mock(AuthPrincipal.class),
                                new WorkspaceId(record.owner().workspaceId()),
                                state,
                                Map.of("private/later.md", "unsaved"),
                                List.of())
                        .code())
                .isEqualTo("RECOVER_MOVE_FIRST");
        verifyNoInteractions(reader, patches, moves);
        assertThat(normalize(
                        SandcrabProtocol.write(
                                StoredAccountCopy.of(record, stored.state().fileBaselines())),
                        "state",
                        "move"))
                .isEqualTo(normalize(encoded, "state", "move"));
        assertThat(normalize(SandcrabProtocol.write(state.snapshot()), "move"))
                .isEqualTo(normalize(fixture("legacy-move-state.json"), "move"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = RetainedLegacyMoveTests.class.getResourceAsStream("/executor/" + name)) {
            return Objects.requireNonNull(input, "Pre-retirement encoded fixture must be present")
                    .readAllBytes();
        }
    }

    private static JsonNode normalize(byte[] encoded, String... movePath) {
        JsonNode json = JSON.readTree(encoded);
        JsonNode move = json;
        for (String path : movePath) {
            move = move.path(path);
        }
        // A set's iteration order is not persisted authority; all other fields and opaque bytes must match.
        var paths = new ArrayList<String>();
        move.path("paths").forEach(path -> paths.add(path.stringValue()));
        ((ObjectNode) move)
                .set("paths", JSON.valueToTree(paths.stream().sorted().toList()));
        return json;
    }
}
