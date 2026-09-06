package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import java.io.IOException;
import java.time.Instant;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.TreeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/** Path walks keep independent merge state and share only immutable, bounded Git objects. */
final class RepositoryHistoryDates {
    static final int MAX_COMMITS = 100_000;
    private static final long MAX_COMMIT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TREE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_DIFFERENCE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_PARSED_TREE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_PATH_STEPS = 10_000_000;
    private final long treeByteLimit;

    RepositoryHistoryDates() {
        this(MAX_TREE_BYTES);
    }

    RepositoryHistoryDates(long treeByteLimit) {
        this.treeByteLimit = treeByteLimit;
    }

    Map<String, Dates> read(Repository repository, String head, List<String> paths) throws IOException {
        if (paths.isEmpty()) return Map.of();
        Map<String, Dates> result = new HashMap<>();
        try (var reader = new BudgetedReader(repository.newObjectReader(), treeByteLimit, paths)) {
            for (int index = 0; index < paths.size(); index++) {
                String path = paths.get(index);
                Instant first = null;
                Instant last = null;
                // TreeRevFilter rewrites parents while following a path. Reusing a RevWalk would
                // leak that path's merge pruning into the next file, even after reset().
                try (var walk = new RevWalk(reader)) {
                    walk.setRevFilter(new HistoryFilter(walk, path, reader, index));
                    walk.markStart(walk.parseCommit(ObjectId.fromString(head)));
                    for (var commit : walk) {
                        Instant at = commit.getCommitterIdent().getWhenAsInstant();
                        if (first == null || at.isBefore(first)) first = at;
                        if (last == null || at.isAfter(last)) last = at;
                    }
                }
                if (first == null) throw new ContentRepositoryException("repository date history is unavailable");
                result.put(path, new Dates(first, last));
            }
        }
        return result;
    }

    private static ContentRepositoryException limit(String subject, long maximum, String unit) {
        return new ContentRepositoryException("repository date history " + subject + " limit exceeded (" + maximum + " "
                + unit + "); structured content is unavailable; inspect repository history size and shape");
    }

    record Dates(Instant createdAt, Instant updatedAt) {}

    private static final class HistoryFilter extends RevFilter {
        private final TreeRevFilter delegate;
        private final BudgetedReader reader;
        private final int index;

        HistoryFilter(RevWalk walk, String path, BudgetedReader reader, int index) {
            this.delegate = new TreeRevFilter(walk, AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF));
            this.reader = reader;
            this.index = index;
        }

        @Override
        public boolean include(RevWalk walk, RevCommit commit) throws IOException {
            if (++reader.pathSteps > MAX_PATH_STEPS) throw limit("path walks", MAX_PATH_STEPS, "commit visits");
            // JGit 7.7 initializes TreeRevFilter's tree readers before shouldTreeWalk(). The
            // negative check must run here to avoid reloading every root tree for every path.
            // Merge commits always use JGit's own parent-pruning rules.
            if (commit.getParentCount() == 1) {
                var parent = commit.getParent(0);
                walk.parseHeaders(parent);
                if (!reader.changed(parent.getTree(), commit.getTree()).get(index)) return false;
            }
            return delegate.include(walk, commit);
        }

        @Override
        public boolean requiresCommitBody() {
            return false;
        }

        @Override
        public RevFilter clone() {
            throw new UnsupportedOperationException("a path history filter belongs to its walk");
        }
    }

    private static final class BudgetedReader extends ObjectReader {
        private final ObjectReader delegate;
        private final long treeLimit;
        private final Map<ObjectId, ObjectLoader> objects = new LinkedHashMap<>(16, 0.75f, true);
        private final Set<ObjectId> commits = new HashSet<>();
        private final Map<String, Integer> indexes = new HashMap<>();
        private final TreeFilter paths;
        private final Map<TreePair, BitSet> differences = new LinkedHashMap<>(16, 0.75f, true);
        private long differenceBytes;
        private long cacheBytes;
        private long commitBytes;
        private long treeBytes;
        private long pathSteps;
        private long parsedTreeBytes;

        BudgetedReader(ObjectReader delegate, long treeLimit, List<String> paths) {
            this.delegate = delegate;
            this.treeLimit = treeLimit;
            for (int i = 0; i < paths.size(); i++) indexes.put(paths.get(i), i);
            this.paths = AndTreeFilter.create(PathFilterGroup.createFromStrings(paths), TreeFilter.ANY_DIFF);
        }

        BitSet changed(ObjectId parent, ObjectId current) throws IOException {
            if (parent.equals(current)) return EMPTY_DIFFERENCE;
            var pair = new TreePair(parent, current);
            BitSet found = differences.get(pair);
            if (found != null) return found;
            BitSet changed = new BitSet();
            try (var tree = new TreeWalk(this)) {
                tree.addTree(parent);
                tree.addTree(current);
                tree.setFilter(paths);
                while (tree.next()) {
                    Integer index = indexes.get(tree.getPathString());
                    if (index != null) changed.set(index);
                    else if (tree.isSubtree()) tree.enterSubtree();
                }
            }
            long retained = changed.size() / 8L + 192;
            while (differenceBytes + retained > MAX_DIFFERENCE_BYTES) {
                BitSet removed =
                        differences.remove(differences.keySet().iterator().next());
                differenceBytes -= removed.size() / 8L + 192;
            }
            // RevTree extends ObjectIdOwnerMap.Entry and can retain the walk through its next link.
            // Only stored keys need detached ids; temporary lookup keys never escape this call.
            differences.put(new TreePair(parent.copy(), current.copy()), changed);
            differenceBytes += retained;
            return changed;
        }

        @Override
        public ObjectLoader open(AnyObjectId id, int type) throws IOException {
            ObjectLoader found = objects.get(id);
            if (found != null) {
                if (type != OBJ_ANY && type != found.getType())
                    throw new org.eclipse.jgit.errors.IncorrectObjectTypeException(id.copy(), type);
                parsed(found);
                return found;
            }
            long size = delegate.getObjectSize(id, type);
            if (type == Constants.OBJ_TREE) {
                if (size > treeLimit - treeBytes) throw limit("tree reads", treeLimit, "bytes");
                treeBytes += size;
            } else {
                if (size > MAX_COMMIT_BYTES - commitBytes) throw limit("commit reads", MAX_COMMIT_BYTES, "bytes");
                commitBytes += size;
            }
            ObjectLoader loaded = delegate.open(id, type);
            parsed(loaded);
            if (loaded.getType() == Constants.OBJ_COMMIT && commits.add(id.copy()) && commits.size() > MAX_COMMITS)
                throw limit("commits", MAX_COMMITS, "commits");
            // Charge retained map/identity overhead as well as payload; tiny objects cannot grow an
            // unbounded entry table. Oversized objects remain bounded by the read budgets above.
            long retained = size + 128;
            if (retained <= MAX_CACHE_BYTES) {
                while (cacheBytes + retained > MAX_CACHE_BYTES) {
                    var removed = objects.remove(objects.keySet().iterator().next());
                    cacheBytes -= removed.getSize() + 128;
                }
                found = new ObjectLoader.SmallObject(loaded.getType(), loaded.getCachedBytes((int) MAX_CACHE_BYTES));
                objects.put(id.copy(), found);
                cacheBytes += retained;
                return found;
            }
            return loaded;
        }

        private void parsed(ObjectLoader loader) {
            if (loader.getType() == Constants.OBJ_TREE) {
                if (loader.getSize() > MAX_PARSED_TREE_BYTES - parsedTreeBytes)
                    throw limit("tree parsing", MAX_PARSED_TREE_BYTES, "bytes");
                parsedTreeBytes += loader.getSize();
            }
        }

        @Override
        public ObjectReader newReader() {
            throw new UnsupportedOperationException("history reads share one resource budget");
        }

        @Override
        public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
            return delegate.resolve(id);
        }

        @Override
        public Set<ObjectId> getShallowCommits() throws IOException {
            return delegate.getShallowCommits();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final BitSet EMPTY_DIFFERENCE = new BitSet();

    private record TreePair(ObjectId parent, ObjectId current) {}
}
