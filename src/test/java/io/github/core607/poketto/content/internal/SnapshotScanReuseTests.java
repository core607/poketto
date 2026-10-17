package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.RepositoryDocument;
import io.github.core607.poketto.content.RepositoryTree;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotScanReuseTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final Predicate<String> eligible = path -> path.startsWith("public/");

    @Test
    void aBuildThatReusesThePreviousOneEqualsAFreshBuildAcrossEdits() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var kept = new JGitRepositoryContentReader(fixture.authority());
        var trees = List.of(
                tree("public/a.md", "# A\n", "public/b.md", "# B\n", "public/c/index.md", "# C\n[a](../a.md)\n"),
                // Change b, add d, rename a without changing it, and switch the folder landing.
                tree(
                        "public/z.md",
                        "# A\n",
                        "public/b.md",
                        "# B two\n",
                        "public/d.md",
                        "---\ntags: [x]\n---\n# D\n",
                        "public/c/README.md",
                        "# C readme\n"),
                // Delete d, make b invalid, add a private file the snapshot must not see.
                tree(
                        "public/z.md",
                        "# A\n",
                        "public/b.md",
                        "---\nid: not-a-uuid\n---\n# B\n",
                        "public/c/README.md",
                        "# C readme\n",
                        "private/p.md",
                        "# P\n"),
                // A document that does not change at all across a commit.
                tree("public/z.md", "# A\n", "public/b.md", "# B three\n", "public/c/README.md", "# C readme\n"));
        RepositoryTree previous = null;
        for (var entries : trees) {
            fixture.commitRemote(workspace, entries);
            RepositoryTree reused = read(fixture, kept);
            RepositoryTree fresh = read(fixture, new JGitRepositoryContentReader(fixture.authority()));
            assertThat(reused).isEqualTo(fresh);
            if (previous != null) {
                var before = document(previous, "public/c/README.md");
                var after = document(reused, "public/c/README.md");
                if (before != null && after != null) {
                    // Reused, not parsed again: the same parsed body instance carries over.
                    assertThat(after.body()).isSameAs(before.body());
                }
            }
            previous = reused;
        }
    }

    private RepositoryTree read(RemoteRepositoryFixture fixture, JGitRepositoryContentReader reader) {
        return fixture.authority()
                .readObjects(workspace, snapshot -> reader.readSnapshot(workspace, snapshot, eligible));
    }

    private static RepositoryDocument document(RepositoryTree tree, String path) {
        return tree.documents().stream()
                .filter(document -> document.file().path().equals(path))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, byte[]> tree(String... pathsAndText) {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(RepositoryPublishingPolicy.PATH, bytes("enabled: true\nmode: public-root\nexclude: []\n"));
        for (int i = 0; i < pathsAndText.length; i += 2) {
            entries.put(pathsAndText[i], bytes(pathsAndText[i + 1]));
        }
        return entries;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
