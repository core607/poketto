package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryHistoryDatesTests {
    @TempDir
    Path directory;

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void threeThousandCommitsDoNotMultiplyTheBudgetByThreeHundredUndatedDocuments() throws Exception {
        var workspace = WorkspaceId.random();
        var fixture = new RemoteRepositoryFixture(directory);
        try (var memory = memory()) {
            ObjectId head = longHistory(memory);
            packInto(memory, head, fixture, workspace);
            var reader = new JGitRepositoryContentReader(fixture.authority());
            long started = System.nanoTime();
            var tree = reader.readTree(workspace, Optional.empty());
            System.out.println("large-history tree read ms=" + (System.nanoTime() - started) / 1_000_000);
            assertThat(tree.documents()).hasSize(334).allSatisfy(document -> {
                assertThat(document.createdAt()).isEqualTo(START);
                assertThat(document.updatedAt()).isEqualTo(START);
            });
            var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
            started = System.nanoTime();
            assertThat(snapshots.refresh(workspace).articles()).hasSize(334);
            System.out.println("large-history public refresh ms=" + (System.nanoTime() - started) / 1_000_000);
            assertThat(snapshots.current(workspace).commit()).contains(head.name());
        }
    }

    @Test
    void mergeChoicesAndBackdatedCommitsMatchThePathFilteredGitHistory() throws Exception {
        try (var repository = memory()) {
            var paths = List.of("first.md", "second.md", "resolved.md", "mode.md", "returned.md");
            Map<String, String> base = new HashMap<>();
            paths.forEach(path -> base.put(path, "# Base"));
            var root = commit(repository, base, START, List.of(), Map.of());
            Map<String, String> left = new HashMap<>(base);
            left.put("first.md", "# Left");
            left.put("resolved.md", "# Left resolution");
            left.remove("returned.md");
            var a = commit(repository, left, START.plusSeconds(20), List.of(root), Map.of());
            Map<String, String> right = new HashMap<>(base);
            right.put("second.md", "# Right");
            right.put("resolved.md", "# Right resolution");
            var b = commit(
                    repository,
                    right,
                    START.minusSeconds(20),
                    List.of(root),
                    Map.of("mode.md", FileMode.EXECUTABLE_FILE));
            Map<String, String> merged = new HashMap<>(base);
            merged.put("first.md", left.get("first.md"));
            merged.put("second.md", right.get("second.md"));
            merged.put("resolved.md", "# Merge resolution");
            merged.put("returned.md", "# Returned");
            var head = commit(repository, merged, START.plusSeconds(40), List.of(a, b), Map.of());
            assertMatchesGit(repository, head, paths);
            var dates = new RepositoryHistoryDates().read(repository, head.name(), paths);
            assertThat(dates.get("first.md")).isEqualTo(new RepositoryHistoryDates.Dates(START, START.plusSeconds(20)));
            assertThat(dates.get("second.md"))
                    .isEqualTo(new RepositoryHistoryDates.Dates(START.minusSeconds(20), START));
            assertThat(dates.get("resolved.md"))
                    .isEqualTo(new RepositoryHistoryDates.Dates(START.minusSeconds(20), START.plusSeconds(40)));
        }
    }

    @Test
    void repeatedMergesDeletionsReintroductionsAndClockSkewMatchIndependentPathWalks() throws Exception {
        try (var repository = memory()) {
            for (int seed = 73442; seed < 73462; seed++) {
                var random = new Random(seed);
                var commits = new ArrayList<ObjectId>();
                var states = new ArrayList<Map<String, String>>();
                var paths = List.of("a.md", "b.md", "c.md", "目录/中文.md");
                for (int i = 0; i < 80; i++) {
                    List<ObjectId> parents = new ArrayList<>();
                    Map<String, String> state = new HashMap<>();
                    if (i > 0) {
                        int parent = random.nextInt(i);
                        parents.add(commits.get(parent));
                        state.putAll(states.get(parent));
                        if (i > 2 && random.nextBoolean()) parents.add(commits.get(random.nextInt(i)));
                    }
                    for (String path : paths) {
                        if (random.nextInt(3) == 0) state.remove(path);
                        else if (random.nextBoolean()) state.put(path, "# " + random.nextInt(4));
                    }
                    var head =
                            commit(repository, state, START.plusSeconds(random.nextInt(100) - 50), parents, Map.of());
                    commits.add(head);
                    states.add(state);
                    if (!state.isEmpty()) assertMatchesGit(repository, head, List.copyOf(state.keySet()));
                }
            }
        }
    }

    @Test
    void actualObjectReadExhaustionIsARepositoryFailureRatherThanMalformedMarkdown() throws Exception {
        try (var repository = memory()) {
            var root = commit(repository, Map.of("a.md", "# One"), START, List.of(), Map.of());
            assertThatThrownBy(() -> new RepositoryHistoryDates(1).read(repository, root.name(), List.of("a.md")))
                    .isInstanceOf(ContentRepositoryException.class)
                    .hasMessageContaining("repository date history tree reads limit exceeded (1 bytes)")
                    .hasMessageContaining("structured content is unavailable");
        }
    }

    @Test
    void mergePruningForOnePathDoesNotCutAnotherPathsHistory() throws Exception {
        try (var repository = memory()) {
            var root = commit(repository, Map.of("a.md", "# Old A", "b.md", "# Old B"), START, List.of(), Map.of());
            var left = commit(repository, Map.of("b.md", "# Old B"), START.plusSeconds(10), List.of(root), Map.of());
            var right = commit(repository, Map.of("a.md", "# New A"), START.minusSeconds(10), List.of(root), Map.of());
            var head = commit(
                    repository,
                    Map.of("a.md", "# New A", "b.md", "# New B"),
                    START.plusSeconds(20),
                    List.of(left, right),
                    Map.of());
            assertMatchesGit(repository, head, List.of("a.md", "b.md"));
            assertMatchesGit(repository, head, List.of("b.md", "a.md"));
        }
    }

    @Test
    void historicalDirectoryAndModeChangesKeepNativePathHistory() throws Exception {
        try (var repository = memory()) {
            var root = commit(
                    repository, Map.of("note.md/child", "old", "renamed.md", "# Source"), START, List.of(), Map.of());
            var edited = commit(
                    repository,
                    Map.of("note.md/child", "changed", "renamed.md", "# Source"),
                    START.plusSeconds(10),
                    List.of(root),
                    Map.of());
            var replaced =
                    commit(repository, Map.of("note.md", "# Source"), START.plusSeconds(20), List.of(edited), Map.of());
            var head = commit(
                    repository,
                    Map.of("note.md", "# Source"),
                    START.minusSeconds(10),
                    List.of(replaced),
                    Map.of("note.md", FileMode.EXECUTABLE_FILE));
            assertMatchesGit(repository, head, List.of("note.md"));
        }
    }

    private static void assertMatchesGit(Repository repository, ObjectId head, List<String> paths) throws Exception {
        var actual = new RepositoryHistoryDates().read(repository, head.name(), paths);
        for (String path : paths) {
            List<Instant> dates = new ArrayList<>();
            try (var walk = new RevWalk(repository)) {
                walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF));
                walk.markStart(walk.parseCommit(head));
                for (var commit : walk) dates.add(commit.getCommitterIdent().getWhenAsInstant());
            }
            assertThat(actual.get(path))
                    .as("%s at %s", path, head.name())
                    .isEqualTo(new RepositoryHistoryDates.Dates(
                            dates.stream().min(Instant::compareTo).orElseThrow(),
                            dates.stream().max(Instant::compareTo).orElseThrow()));
        }
    }

    private static ObjectId longHistory(Repository repository) throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            var entries = new java.util.TreeMap<String, ObjectId>();
            for (int i = 0; i < 334; i++)
                entries.put(
                        "文章%03d.md".formatted(i),
                        inserter.insert(Constants.OBJ_BLOB, ("# Article " + i).getBytes(StandardCharsets.UTF_8)));
            var policy = new org.eclipse.jgit.lib.TreeFormatter();
            policy.append(
                    "publishing.yaml",
                    FileMode.REGULAR_FILE,
                    inserter.insert(
                            Constants.OBJ_BLOB,
                            "enabled: true\nmode: public-by-default\n".getBytes(StandardCharsets.UTF_8)));
            entries.put(".poketto", inserter.insert(policy));
            ObjectId head = null;
            // Distinct roots exercise real differences. One inserter avoids thousands of tiny
            // in-memory packs and repeated lookup costs while constructing the isolated fixture.
            for (int i = 0; i < 3001; i++) {
                entries.put(
                        "counter.txt",
                        inserter.insert(Constants.OBJ_BLOB, ("counter " + i).getBytes(StandardCharsets.UTF_8)));
                var tree = new org.eclipse.jgit.lib.TreeFormatter();
                for (var entry : entries.entrySet())
                    tree.append(
                            entry.getKey(),
                            entry.getKey().equals(".poketto") ? FileMode.TREE : FileMode.REGULAR_FILE,
                            entry.getValue());
                var commit = new CommitBuilder();
                commit.setTreeId(inserter.insert(tree));
                if (head != null) commit.setParentId(head);
                var identity = new PersonIdent("Fixture", "fixture@invalid", START.plusSeconds(i), ZoneOffset.UTC);
                commit.setAuthor(identity);
                commit.setCommitter(identity);
                commit.setMessage("history fixture");
                head = inserter.insert(commit);
            }
            inserter.flush();
            return head;
        }
    }

    private static InMemoryRepository memory() {
        return new InMemoryRepository(new DfsRepositoryDescription("date history fixture"));
    }

    private static ObjectId commit(
            Repository repository,
            Map<String, String> files,
            Instant at,
            List<ObjectId> parents,
            Map<String, FileMode> modes)
            throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            var cache = DirCache.newInCore();
            var builder = cache.builder();
            for (String path : files.keySet().stream().sorted().toList()) {
                var entry = new DirCacheEntry(path);
                entry.setFileMode(modes.getOrDefault(path, FileMode.REGULAR_FILE));
                entry.setObjectId(
                        inserter.insert(Constants.OBJ_BLOB, files.get(path).getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            commit.setTreeId(cache.writeTree(inserter));
            commit.setParentIds(parents);
            var identity = new PersonIdent("Fixture", "fixture@invalid", at, ZoneOffset.UTC);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("history fixture");
            var id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    private static void packInto(
            Repository memory, ObjectId head, RemoteRepositoryFixture fixture, WorkspaceId workspace) throws Exception {
        // Pack the synthetic history before remote transport, avoiding thousands of loose files on
        // Windows while exercising the production authority and reader against a real on-disk repo.
        try (var remote = fixture.openRemote(workspace);
                var writer = new PackWriter(memory)) {
            writer.preparePack(NullProgressMonitor.INSTANCE, Set.of(head), Set.of());
            String name = "pack-" + writer.computeName().name();
            Path packs = remote.getDirectory().toPath().resolve("objects/pack");
            Files.createDirectories(packs);
            try (var output = Files.newOutputStream(packs.resolve(name + ".pack"))) {
                writer.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, output);
            }
            try (var output = Files.newOutputStream(packs.resolve(name + ".idx"))) {
                writer.writeIndex(output);
            }
            RefUpdate update = remote.updateRef(Constants.R_HEADS + "main");
            update.setNewObjectId(head);
            assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW);
        }
    }
}
