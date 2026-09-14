package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryFilenamePage;
import io.github.core607.poketto.content.RepositoryFilenameSearch;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryFilenameSearchTests {
    private static final WorkspaceId WORKSPACE = WorkspaceId.random();
    private static final RepositoryMediaIndex.Media MEDIA = new RepositoryMediaIndex.Media(
            UUID.fromString("ae821d0c-f3e4-4a29-a1d9-ce4e73f75008"), "b".repeat(64), "video/mp4", 32_000_000);

    @TempDir
    Path directory;

    @Test
    void searchesRegularAndIndexedPathsInPinnedJavaOrderWithoutFollowingLinks() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var index = new RepositoryMediaIndex(Map.of(
                "private/nested/needle.mp4", MEDIA,
                "public/needle.png", MEDIA));
        var files = new LinkedHashMap<String, byte[]>();
        files.put(RepositoryMediaIndex.PATH, index.encode());
        files.put("private/nested/needle.md", bytes("# private"));
        files.put("public/needle.txt", bytes("public"));
        files.put("link-needle", bytes("private/nested/needle.md"));
        files.put("submodule-needle", new byte[0]);
        var modes = Map.of("link-needle", FileMode.SYMLINK, "submodule-needle", FileMode.GITLINK);
        ObjectId first = fixture.commitRemote(WORKSPACE, files, modes);
        var reader = new JGitRepositoryContentReader(fixture.authority());

        RepositoryFilenamePage page =
                reader.searchFilenames(WORKSPACE, Optional.empty(), new RepositoryFilenameSearch("needle", 0, 50));
        assertThat(page.commit()).isEqualTo(first.name());
        assertThat(page.paths())
                .containsExactly(
                        "private/nested/needle.md",
                        "private/nested/needle.mp4",
                        "public/needle.png",
                        "public/needle.txt");
        assertThat(page.total()).isEqualTo(4);

        files.put("aaa/newneedle.txt", bytes("later"));
        fixture.commitRemote(WORKSPACE, files, modes);
        RepositoryFilenamePage remainder = reader.searchFilenames(
                WORKSPACE, Optional.of(first.name()), new RepositoryFilenameSearch("needle", 2, 2));
        assertThat(remainder.commit()).isEqualTo(first.name());
        assertThat(remainder.paths()).containsExactly("public/needle.png", "public/needle.txt");
        assertThat(remainder.total()).isEqualTo(4);
    }

    @Test
    void publicSearchFiltersPrivatePathsAndInvalidContinuationsBeforeReadingGit() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId commit = fixture.commitRemote(
                WORKSPACE,
                Map.of(
                        ".poketto/publishing.yaml", bytes("enabled: true\nmode: public-root\n"),
                        "private/needle.md", bytes("private"),
                        "public/needle.md", bytes("public")));
        var reader = new JGitRepositoryContentReader(fixture.authority());

        RepositoryFilenamePage page = reader.searchPublicFilenames(
                WORKSPACE, Optional.empty(), new RepositoryFilenameSearch("needle", 0, 50));
        assertThat(page.commit()).isEqualTo(commit.name());
        assertThat(page.paths()).containsExactly("public/needle.md");
        assertThatThrownBy(() -> reader.searchPublicFilenames(
                        WORKSPACE, Optional.empty(), new RepositoryFilenameSearch("needle", 1, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned commit");
    }

    @Test
    void mediaOverlayCollisionFailsBeforeReturningAnIncompleteNamespace() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var index = new RepositoryMediaIndex(Map.of("private/dir/file.bin", MEDIA));
        fixture.commitRemote(
                WORKSPACE, Map.of(RepositoryMediaIndex.PATH, index.encode(), "private/dir", bytes("file")));
        var reader = new JGitRepositoryContentReader(fixture.authority());

        assertThatThrownBy(() -> reader.searchFilenames(
                        WORKSPACE, Optional.empty(), new RepositoryFilenameSearch("file", 0, 50)))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessage("Repository media paths collide with Git entries");
    }

    @Test
    void rejectsInvalidBoundsAndUnboundedContinuations() throws Exception {
        var reader = new JGitRepositoryContentReader(new RemoteRepositoryFixture(directory).authority());

        assertThatThrownBy(() -> new RepositoryFilenameSearch("", 0, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryFilenameSearch("needle", 100_001, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryFilenameSearch("needle", 0, 201))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.searchFilenames(
                        WORKSPACE, Optional.empty(), new RepositoryFilenameSearch("needle", 1, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned commit");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
