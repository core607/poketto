package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

class PublishingPolicyCacheTests {

    @Test
    void aCommitsPolicyIsReadOnceAndServedAgainFromTheCache() throws Exception {
        var repository = new InMemoryRepository(new DfsRepositoryDescription("policy"));
        String commit = commit(repository, "enabled: true\nmode: public-root\n").name();

        RepositoryPublishingPolicy first;
        try (var objects = repository.newObjectReader()) {
            first = JGitPublicContentSnapshots.policy(objects, commit);
        }
        RepositoryPublishingPolicy second;
        try (var objects = repository.newObjectReader()) {
            second = JGitPublicContentSnapshots.policy(objects, commit);
        }

        assertThat(first.state()).isEqualTo(RepositoryPublishingPolicy.State.ENABLED);
        assertThat(second).isSameAs(first);
    }

    @Test
    void aCachedCommitMissingFromAnotherRepositoryIsStillAnError() throws Exception {
        var source = new InMemoryRepository(new DfsRepositoryDescription("source"));
        String commit = commit(source, "enabled: true\nmode: public-root\n").name();
        try (var objects = source.newObjectReader()) {
            JGitPublicContentSnapshots.policy(objects, commit);
        }
        var other = new InMemoryRepository(new DfsRepositoryDescription("other"));

        try (var objects = other.newObjectReader()) {
            assertThatThrownBy(() -> JGitPublicContentSnapshots.policy(objects, commit))
                    .isInstanceOf(ContentRepositoryException.class);
        }
    }

    private static ObjectId commit(InMemoryRepository repository, String policy) throws Exception {
        try (var inserter = repository.newObjectInserter()) {
            var cache = DirCache.newInCore();
            var builder = cache.builder();
            var entry = new DirCacheEntry(RepositoryPublishingPolicy.PATH);
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, policy.getBytes(StandardCharsets.UTF_8)));
            builder.add(entry);
            builder.finish();
            var commit = new CommitBuilder();
            commit.setTreeId(cache.writeTree(inserter));
            commit.setParentIds(List.of());
            var identity =
                    new PersonIdent("Author", "author@invalid", Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("policy\n");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }
}
