package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentRepositoryBootstrapTests {

    @TempDir
    Path dataDirectory;

    @Test
    void initializesAbsentAndEmptyDirectoriesAsUnbornMainRepositories() throws Exception {
        WorkspacePaths paths = paths();
        ContentRepositoryStore store = store(paths);
        WorkspaceId absent = WorkspaceId.random();
        WorkspaceId empty = WorkspaceId.random();
        Files.createDirectories(paths.contentDirectory(empty));

        store.ensureReady(absent);
        store.ensureReady(empty);

        assertUnbornMain(paths.contentDirectory(absent));
        assertUnbornMain(paths.contentDirectory(empty));
    }

    @Test
    void acceptsAnExistingMainWorktreeAndPreservesItsConfiguration() throws Exception {
        WorkspacePaths paths = paths();
        WorkspaceId workspaceId = WorkspaceId.random();
        Path content = paths.contentDirectory(workspaceId);
        Files.createDirectories(content);
        try (Git git = Git.init().setInitialBranch("main").setDirectory(content.toFile()).call()) {
            git.getRepository().getConfig().setString(
                    "remote", "backup", "url", "https://example.invalid/content.git");
            git.getRepository().getConfig().save();
        }

        store(paths).ensureReady(workspaceId);

        try (var repository = new FileRepositoryBuilder()
                .setWorkTree(content.toFile())
                .findGitDir(content.toFile())
                .build()) {
            assertThat(repository.getFullBranch()).isEqualTo("refs/heads/main");
            assertThat(repository.getConfig().getString("remote", "backup", "url"))
                    .isEqualTo("https://example.invalid/content.git");
        }
    }

    @Test
    void rejectsNonEmptyDirectoriesThatAreNotRepositories() throws Exception {
        WorkspacePaths paths = paths();
        WorkspaceId workspaceId = WorkspaceId.random();
        Path content = paths.contentDirectory(workspaceId);
        Files.createDirectories(content);
        Files.writeString(content.resolve("existing.md"), "operator content");

        assertThatThrownBy(() -> store(paths).ensureReady(workspaceId))
                .hasMessageContaining(workspaceId.toString())
                .hasMessageContaining(content.toString())
                .hasMessageContaining("choose an empty directory or initialize and commit");
    }

    @Test
    void rejectsBareRepositories() throws Exception {
        WorkspacePaths paths = paths();
        WorkspaceId workspaceId = WorkspaceId.random();
        Path content = paths.contentDirectory(workspaceId);
        Files.createDirectories(content.getParent());
        try (Git ignored = Git.init().setBare(true).setDirectory(content.toFile()).call()) {
            // The bare repository is the invalid fixture.
        }

        assertThatThrownBy(() -> store(paths).ensureReady(workspaceId))
                .hasMessageContaining("bare repositories are not accepted");
    }

    @Test
    void rejectsAWorktreeOnTheWrongBranch() throws Exception {
        WorkspacePaths paths = paths();
        WorkspaceId workspaceId = WorkspaceId.random();
        Path content = paths.contentDirectory(workspaceId);
        Files.createDirectories(content);
        try (Git git = Git.init().setInitialBranch("main").setDirectory(content.toFile()).call()) {
            Files.writeString(content.resolve("README.md"), "fixture");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("fixture")
                    .setAuthor("Poketto Tests", "tests@invalid.example")
                    .setCommitter("Poketto Tests", "tests@invalid.example")
                    .call();
            git.branchCreate().setName("other").call();
            git.checkout().setName("other").call();
        }

        assertThatThrownBy(() -> store(paths).ensureReady(workspaceId))
                .hasMessageContaining("must have main checked out")
                .hasMessageContaining("refs/heads/other");
    }

    @Test
    void rejectsRepositoryMetadataThatCannotResolveHead() throws Exception {
        WorkspacePaths paths = paths();
        WorkspaceId workspaceId = WorkspaceId.random();
        Path content = paths.contentDirectory(workspaceId);
        Files.createDirectories(content);
        try (Git ignored = Git.init().setInitialBranch("main").setDirectory(content.toFile()).call()) {
            // Corrupt the recognized repository after initialization.
        }
        Files.writeString(content.resolve(".git/HEAD"), "not a symbolic ref\n");

        assertThatThrownBy(() -> store(paths).ensureReady(workspaceId))
                .hasMessageContaining("repository metadata cannot be read");
    }

    private WorkspacePaths paths() {
        return new WorkspacePaths(dataDirectory.toAbsolutePath());
    }

    private static ContentRepositoryStore store(WorkspacePaths paths) {
        return new JGitContentRepositoryStore(paths, new CanonicalDocumentCodec());
    }

    private static void assertUnbornMain(Path content) throws Exception {
        try (var repository = new FileRepositoryBuilder()
                .setWorkTree(content.toFile())
                .findGitDir(content.toFile())
                .build()) {
            assertThat(repository.isBare()).isFalse();
            assertThat(repository.getFullBranch()).isEqualTo(Constants.R_HEADS + "main");
            assertThat(repository.resolve(Constants.HEAD)).isNull();
        }
    }
}
