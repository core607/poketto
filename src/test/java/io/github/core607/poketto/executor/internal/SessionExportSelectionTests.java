package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionExportSelectionTests {
    private final RepositorySnapshotExports.PublicExport projection = new RepositorySnapshotExports.PublicExport(
            WorkspaceId.random(),
            new RepositorySnapshotExports.Export(UUID.randomUUID(), "projected", "digest", 1),
            "host-only-commit",
            "projection-digest",
            Map.of(
                    "articles/1.md",
                    "public/original.md",
                    "articles/2.md",
                    "published/other.md",
                    "media/1.pdf",
                    "public/files/original.pdf"),
            Map.of());

    @Test
    void onlyVisiblePathsAndTheirDirectoriesTranslateIntoHostOwnedSourcePaths() {
        assertThat(SessionExportSelection.resolve(List.of("articles"), projection))
                .containsExactly("public/original.md", "published/other.md");
        assertThat(SessionExportSelection.resolve(List.of("articles/1.md", "media"), projection))
                .containsExactly("public/files/original.pdf", "public/original.md");
        for (String forged :
                List.of("public/original.md", "public", "articles/3.md", "articles-other", "private/secret.md"))
            assertThatThrownBy(() -> SessionExportSelection.resolve(List.of(forged), projection))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootAndOverlappingSelectionsNeverInventOrDuplicateSourceCoordinates() {
        assertThat(SessionExportSelection.resolve(List.of(".", "articles"), projection))
                .containsExactly("public/files/original.pdf", "public/original.md", "published/other.md");
        assertThat(SessionExportSelection.resolve(List.of(".", "private/notes"), null))
                .containsExactly("", "private/notes");
        for (List<String> bad : List.of(
                List.<String>of(),
                List.of("a.md", "a.md"),
                List.of("../secret"),
                List.of(".git/config"),
                List.of("agents.md")))
            assertThatThrownBy(() -> SessionExportSelection.resolve(bad, projection))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
