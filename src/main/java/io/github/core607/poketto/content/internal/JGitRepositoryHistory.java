package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryHistoryPage;
import io.github.core607.poketto.content.RepositoryHistoryQuery;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Literal-path mainline history; never checks out historical files or follows renamed paths. */
final class JGitRepositoryHistory {
    private static final int MAX_SCANNED_PER_PAGE = 256;
    private static final int MAX_COMMIT_BYTES = 1024 * 1024;
    private static final long MAX_READ_NANOS = Duration.ofSeconds(2).toNanos();

    private JGitRepositoryHistory() {}

    static RepositoryHistoryPage read(Repository repository, Optional<String> selected, RepositoryHistoryQuery query)
            throws IOException {
        if (selected.isEmpty()) {
            return new RepositoryHistoryPage(null, query.path(), List.of(), null);
        }
        long deadline = System.nanoTime() + MAX_READ_NANOS;
        try (var walk = new RevWalk(repository)) {
            walk.setRetainBody(false);
            RevCommit current = boundedCommit(repository, walk, ObjectId.fromString(selected.orElseThrow()));
            for (int skipped = 0; current != null && skipped < query.offset(); skipped++) {
                checkDeadline(deadline);
                current = parent(repository, walk, current);
            }
            return page(repository, walk, selected.orElseThrow(), query, current, deadline);
        }
    }

    private static RepositoryHistoryPage page(
            Repository repository,
            RevWalk walk,
            String selected,
            RepositoryHistoryQuery query,
            RevCommit current,
            long deadline)
            throws IOException {
        List<RepositoryHistoryPage.Entry> entries = new ArrayList<>();
        int scanned = 0;
        while (current != null && scanned < MAX_SCANNED_PER_PAGE && entries.size() < query.limit()) {
            checkDeadline(deadline);
            if (query.offset() + scanned >= RepositoryHistoryDates.MAX_COMMITS) {
                throw new ContentRepositoryException("repository history commit limit exceeded");
            }
            RevCommit parent = parent(repository, walk, current);
            Blob before = blob(repository, parent, query.path());
            Blob after = blob(repository, current, query.path());
            if (!Objects.equals(before, after)) {
                walk.parseBody(current);
                entries.add(new RepositoryHistoryPage.Entry(
                        current.name(),
                        bounded(current.getShortMessage(), 240),
                        bounded(current.getAuthorIdent().getName(), 120),
                        Instant.ofEpochSecond(current.getCommitTime()),
                        after != null));
            }
            current.disposeBody();
            scanned++;
            current = parent;
        }
        return new RepositoryHistoryPage(
                selected, query.path(), entries, current == null ? null : query.offset() + scanned);
    }

    static boolean reachable(Repository repository, String head, String candidate) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
            int count = 0;
            for (RevCommit commit : walk) {
                if (++count > RepositoryHistoryDates.MAX_COMMITS) {
                    throw new ContentRepositoryException("repository history limit exceeded");
                }
                if (commit.name().equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static RevCommit parent(Repository repository, RevWalk walk, RevCommit commit) throws IOException {
        return commit.getParentCount() == 0 ? null : boundedCommit(repository, walk, commit.getParent(0));
    }

    private static RevCommit boundedCommit(Repository repository, RevWalk walk, ObjectId id) throws IOException {
        if (repository.open(id, Constants.OBJ_COMMIT).getSize() > MAX_COMMIT_BYTES) {
            throw new ContentRepositoryException("repository history commit metadata exceeds its byte limit");
        }
        return walk.parseCommit(id);
    }

    private static Blob blob(Repository repository, RevCommit commit, String path) throws IOException {
        if (commit == null) {
            return null;
        }
        try (TreeWalk entry = TreeWalk.forPath(repository, path, commit.getTree())) {
            return entry == null
                    ? null
                    : new Blob(entry.getObjectId(0), entry.getFileMode(0).getBits());
        }
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline) {
            throw new ContentRepositoryException("repository history read time limit exceeded");
        }
    }

    private static String bounded(String text, int maximum) {
        int count = text.codePointCount(0, text.length());
        return count <= maximum ? text : text.substring(0, text.offsetByCodePoints(0, maximum));
    }

    private record Blob(ObjectId id, int mode) {}
}
