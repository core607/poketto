package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.RepositoryDirectoryPage;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.FileMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryDirectoryReaderTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void listsImmediateTrackedChildrenWithoutParsingOrReadingTheirBodies() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(
                workspace,
                Map.of(
                        "AGENTS.md", bytes("---\ninvalid: [\n---\n"),
                        "notes/AGENTS.md", bytes("# Notes"),
                        "notes/log.txt", bytes("A plain text record"),
                        "notes/中文 空格%#.md", bytes("# Exact name"),
                        "large.bin", new byte[ContentLimits.MAX_DOCUMENT_BYTES + 1],
                        "invalid-utf8.txt", new byte[] {(byte) 0xff},
                        ".poketto/publishing.yaml", bytes("enabled: false")));
        var reader = new JGitRepositoryContentReader(fixture.authority());

        var root = reader.listDirectory(workspace, Optional.empty(), "", 0, 100);
        assertThat(root.commit()).contains(commit.name());
        assertThat(root.expectedAbsence()).isFalse();
        assertThat(root.entries())
                .containsExactly(
                        entry(".poketto", RepositoryDirectoryPage.Kind.DIRECTORY),
                        entry("AGENTS.md", RepositoryDirectoryPage.Kind.FILE),
                        entry("invalid-utf8.txt", RepositoryDirectoryPage.Kind.FILE),
                        entry("large.bin", RepositoryDirectoryPage.Kind.FILE),
                        entry("notes", RepositoryDirectoryPage.Kind.DIRECTORY));
        assertThat(root.nextOffset()).isNull();

        assertThat(reader.listDirectory(workspace, root.commit(), "notes", 0, 100)
                        .entries())
                .extracting(RepositoryDirectoryPage.Entry::path)
                .containsExactly("notes/AGENTS.md", "notes/log.txt", "notes/中文 空格%#.md");
        assertThat(Files.exists(fixture.cache(workspace).resolve("large.bin"))).isFalse();
    }

    @Test
    void continuationUsesTheSameTreeDespiteRemoteAdvancementAndCacheWorktreeEdits() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("a.md", bytes("A"));
        files.put("b/AGENTS.md", bytes("B"));
        files.put("c.txt", bytes("C"));
        files.put("d.md", bytes("D"));
        fixture.commitRemote(workspace, files);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var first = reader.listDirectory(workspace, Optional.empty(), "", 0, 2);
        assertThat(first.entries())
                .extracting(RepositoryDirectoryPage.Entry::path)
                .containsExactly("a.md", "b");
        assertThat(first.nextOffset()).isEqualTo(2);
        files.put("aa.txt", bytes("Later addition"));
        fixture.commitRemote(workspace, files);
        Files.writeString(fixture.cache(workspace).resolve("not-committed.md"), "Local only");

        var second = reader.listDirectory(workspace, first.commit(), "", first.nextOffset(), 2);
        assertThat(second.commit()).isEqualTo(first.commit());
        assertThat(second.entries())
                .extracting(RepositoryDirectoryPage.Entry::path)
                .containsExactly("c.txt", "d.md");
        assertThat(second.nextOffset()).isNull();
        assertThat(reader.listDirectory(workspace, Optional.empty(), "", 0, 100).entries())
                .extracting(RepositoryDirectoryPage.Entry::path)
                .containsExactly("a.md", "aa.txt", "b", "c.txt", "d.md");
    }

    @Test
    void missingDirectoriesAreDistinctFromFilesAndAnUnbornRoot() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var empty = reader.listDirectory(workspace, Optional.empty(), "", 0, 100);
        assertThat(empty.commit()).isEmpty();
        assertThat(empty.expectedAbsence()).isFalse();
        assertThat(empty.entries()).isEmpty();
        assertThat(reader.listDirectory(workspace, Optional.empty(), "missing", 0, 100)
                        .expectedAbsence())
                .isTrue();
        fixture.commitRemote(workspace, Map.of("file.md", bytes("# File")));
        assertThat(reader.listDirectory(workspace, Optional.empty(), "missing", 0, 100)
                        .expectedAbsence())
                .isTrue();
        assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.empty(), "file.md", 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void reportsSymlinksWithoutFollowingThem() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(
                workspace,
                Map.of("link", bytes("private"), "private/note.md", bytes("# Private")),
                Map.of("link", FileMode.SYMLINK));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.listDirectory(workspace, Optional.empty(), "", 0, 100).entries())
                .containsExactly(
                        entry("link", RepositoryDirectoryPage.Kind.SYMLINK),
                        entry("private", RepositoryDirectoryPage.Kind.DIRECTORY));
        assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.empty(), "link", 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOtherWorkspaceCommitsAndKeepsTheirListingsSeparate() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var first = fixture.commitRemote(workspace, Map.of("first.md", bytes("First")));
        var other = WorkspaceId.random();
        fixture.commitRemote(other, Map.of("second.md", bytes("Second")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.listDirectory(other, Optional.empty(), "", 0, 100).entries())
                .extracting(RepositoryDirectoryPage.Entry::path)
                .containsExactly("second.md");
        assertThatThrownBy(() -> reader.listDirectory(other, Optional.of(first.name()), "", 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafePathsAndUnpinnedOrUnboundedPagesBeforeRepositoryAccess() {
        var reader = new JGitRepositoryContentReader(new RemoteRepositoryFixture(directory).authority());
        for (String path : new String[] {"..", "notes/../private", "/notes", "notes/", ".git", "notes\\child"}) {
            assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.empty(), path, 0, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.empty(), "", 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        for (int limit : new int[] {0, 201}) {
            assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.empty(), "", 0, limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.empty(), "", -1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.listDirectory(workspace, Optional.of("0".repeat(40)), "", 100001, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RepositoryDirectoryPage.Entry entry(String path, RepositoryDirectoryPage.Kind kind) {
        return new RepositoryDirectoryPage.Entry(path, kind);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
