package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.PublicRevisionHistory.Revision;
import io.github.core607.poketto.content.PublicRevisionHistory.Revisions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

class JGitPublicRevisionHistoryTests {
    private static final String ENABLED = "enabled: true\nmode: public-root\n";
    private static final String DISABLED = "enabled: false\nmode: public-root\n";
    private static final String PATH = "public/essay.md";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private final InMemoryRepository repository = new InMemoryRepository(new DfsRepositoryDescription("history"));
    private ObjectId head;
    private int commits;

    @Test
    void anUnpublishedStretchEndsHistoryEvenIfTheArticleWasPublicBefore() throws Exception {
        commit(ENABLED, "# Essay\n\nwritten in public");
        commit(DISABLED, "# Essay\n\nwritten while closed");
        commit(ENABLED, "# Essay\n\nfirst public text");
        commit(ENABLED, "# Essay\n\ncorrected text");

        assertThat(bodies(read("/essay"))).containsExactly("# Essay\n\ncorrected text", "# Essay\n\nfirst public text");
        assertThat(read("/essay").complete()).isTrue();
    }

    @Test
    void exclusionsPrivatePathsAndAbsenceEndHistory() throws Exception {
        commit(ENABLED, "earliest");
        commit(ENABLED + "exclude:\n  - public/essay.md\n", "excluded");
        commit(ENABLED, "after the exclusion");
        assertThat(bodies(read("/essay"))).containsExactly("after the exclusion");

        head = null;
        commit(ENABLED, Map.of("public/other.md", "absent here"));
        commit(ENABLED, "appeared");
        assertThat(bodies(read("/essay"))).containsExactly("appeared");
    }

    @Test
    void identicalBodiesCollapseIntoTheirEarliestCommit() throws Exception {
        commit(ENABLED, "---\ntitle: One\n---\nsame body");
        commit(ENABLED, "---\ntitle: Two\n---\nsame body");
        commit(ENABLED, Map.of(PATH, "---\ntitle: Two\n---\nsame body", "public/other.md", "unrelated"));
        commit(ENABLED, "---\ntitle: Two\n---\nnew body");

        var revisions = read("/essay").newestFirst();
        assertThat(revisions)
                .containsExactly(new Revision(START.plusSeconds(3 * 60), "new body"), new Revision(START, "same body"));
    }

    @Test
    void aRouteChangeOrAFutureReleaseEndsHistory() throws Exception {
        commit(ENABLED, "---\nroute: /old\n---\nat the old route");
        commit(ENABLED, "at the path route");
        assertThat(bodies(read("/essay"))).containsExactly("at the path route");

        head = null;
        commit(ENABLED, "---\npublish_at: 2026-12-01T00:00:00Z\n---\nscheduled for later");
        commit(ENABLED, "---\npublish_at: 2026-09-02T00:00:00Z\n---\nreleased");
        assertThat(bodies(read("/essay"))).containsExactly("released");
    }

    @Test
    void theVersionBoundReportsAnIncompleteHistory() throws Exception {
        for (int version = 0; version <= JGitPublicRevisionHistory.MAX_VERSIONS; version++) {
            commit(ENABLED, "version " + version);
        }
        var revisions = read("/essay");
        assertThat(revisions.newestFirst()).hasSize(JGitPublicRevisionHistory.MAX_VERSIONS);
        assertThat(revisions.newestFirst().getFirst().body()).isEqualTo("version 50");
        assertThat(revisions.complete()).isFalse();
    }

    private Revisions read(String route) throws Exception {
        try (var objects = repository.newObjectReader()) {
            return JGitPublicRevisionHistory.walk(objects, head, PATH, route, NOW, System.nanoTime() + 10_000_000_000L);
        }
    }

    private static List<String> bodies(Revisions revisions) {
        return revisions.newestFirst().stream().map(Revision::body).toList();
    }

    private void commit(String policy, String essay) throws Exception {
        commit(policy, Map.of(PATH, essay));
    }

    private void commit(String policy, Map<String, String> files) throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            var cache = DirCache.newInCore();
            var builder = cache.builder();
            var all = new TreeMap<>(files);
            all.put(RepositoryPublishingPolicy.PATH, policy);
            for (var file : all.entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            commit.setTreeId(cache.writeTree(inserter));
            commit.setParentIds(head == null ? List.of() : List.of(head));
            var identity = new PersonIdent(
                    "Private Name", "private@invalid", START.plusSeconds(60L * commits++), ZoneOffset.UTC);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("private message");
            head = inserter.insert(commit);
            inserter.flush();
        }
    }
}
