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
    private final UUID workspace = UUID.randomUUID();

    @Test
    void publicCopyRetainsItsOriginalAuthorityAndHostOnlyMapping() {
        RepositorySnapshotExports.PublicExport projection = projection(workspace, BASE);
        AccountCopyRecord record = record(false, projection);

        AccountCopyRecord restored = JSON.readValue(JSON.writeValueAsBytes(record), AccountCopyRecord.class);

        assertThat(restored.publicExport()).isEqualTo(projection);
        assertThat(restored.publicExport().authorityCommit()).isEqualTo("2".repeat(40));
        assertThat(restored.state().originalCommit()).isEqualTo(BASE);
        assertThat(restored.publicExport().sourcePaths()).containsEntry("article.md", "public/notes/article.md");
    }

    @Test
    void rejectsPublicCopiesWithoutProofOrWithAnotherWorkspaceOrBaseline() {
        assertThatThrownBy(() -> record(false, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> record(false, projection(UUID.randomUUID(), BASE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
        assertThatThrownBy(() -> record(false, projection(workspace, "3".repeat(40))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned");
        assertThatThrownBy(() -> record(true, projection(workspace, BASE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full copy");
    }

    private AccountCopyRecord record(boolean fullRead, RepositorySnapshotExports.PublicExport projection) {
        return new AccountCopyRecord(
                1,
                new AccountCopyRecord.Owner(UUID.randomUUID(), workspace, fullRead),
                UUID.randomUUID(),
                0,
                1_800_000_000_000L,
                new AccountCopyRecord.Writer(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                AccountCopyRecord.Phase.READY,
                null,
                null,
                new SelectedFileSaves.State(BASE).snapshot(),
                projection,
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
