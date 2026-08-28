package io.github.core607.poketto.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTests {

    @TempDir
    Path dataDirectory;

    @Test
    void resolvesEachWorkspaceBelowItsOwnCanonicalDirectory() {
        WorkspaceId first = WorkspaceId.random();
        WorkspaceId second = WorkspaceId.random();
        WorkspacePaths paths = new WorkspacePaths(dataDirectory);

        Path firstContent = paths.contentDirectory(first);
        Path secondContent = paths.contentDirectory(second);

        assertThat(firstContent)
                .isEqualTo(dataDirectory.resolve("workspaces").resolve(first.toString()).resolve("content"));
        assertThat(secondContent)
                .isEqualTo(dataDirectory.resolve("workspaces").resolve(second.toString()).resolve("content"));
        assertThat(firstContent).isNotEqualTo(secondContent);
        assertThat(firstContent.startsWith(dataDirectory.resolve("workspaces"))).isTrue();
        assertThat(secondContent.startsWith(dataDirectory.resolve("workspaces"))).isTrue();
    }

    @Test
    void rejectsRelativeDataDirectory() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkspacePaths(Path.of("data")))
                .withMessageContaining("must be absolute");
    }
}
