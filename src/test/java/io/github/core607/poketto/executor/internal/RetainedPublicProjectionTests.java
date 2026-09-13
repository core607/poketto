package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetainedPublicProjectionTests {
    private static final String BASE = "1".repeat(40);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RetainedCopyRecord.Owner owner = new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID());

    @Test
    void publicCopyRetainsItsOriginalAuthorityAndHostOnlyMapping() {
        RepositorySnapshotExports.PublicExport projection = projection(owner.workspaceId(), BASE);
        RetainedCopyRecord record = record(false, projection);

        RetainedCopyRecord restored = JSON.readValue(JSON.writeValueAsBytes(record), RetainedCopyRecord.class);

        assertThat(restored.publicExport()).isEqualTo(projection);
        assertThat(restored.publicExport().authorityCommit()).isEqualTo("2".repeat(40));
        assertThat(restored.acknowledged().state().originalCommit()).isEqualTo(BASE);
        assertThat(restored.publicExport().sourcePaths()).containsEntry("article.md", "public/notes/article.md");
    }

    @Test
    void rejectsPublicCopiesWithoutProofOrWithAnotherWorkspaceOrBaseline() {
        assertThatThrownBy(() -> record(false, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> record(false, projection(UUID.randomUUID(), BASE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
        assertThatThrownBy(() -> record(false, projection(owner.workspaceId(), "3".repeat(40))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned");
        assertThatThrownBy(() -> record(true, projection(owner.workspaceId(), BASE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full copy");
    }

    private RetainedCopyRecord record(boolean fullRead, RepositorySnapshotExports.PublicExport projection) {
        return new RetainedCopyRecord(
                1,
                owner,
                UUID.randomUUID(),
                0,
                1,
                "a".repeat(64),
                fullRead,
                projection,
                1_800_000_000_000L,
                new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID()),
                new RetainedCopyRecord.Checkpoint(
                        UUID.randomUUID(), "b".repeat(64), 100, new SelectedFileSaves.State(BASE).snapshot()),
                null);
    }

    private static RepositorySnapshotExports.PublicExport projection(UUID workspace, String commit) {
        return new RepositorySnapshotExports.PublicExport(
                new WorkspaceId(workspace),
                new RepositorySnapshotExports.Export(UUID.randomUUID(), commit, "c".repeat(64), 100),
                "2".repeat(40),
                "d".repeat(64),
                Map.of("article.md", "public/notes/article.md"),
                Map.of());
    }
}
