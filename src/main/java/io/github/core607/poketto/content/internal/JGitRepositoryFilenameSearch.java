package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryFilenamePage;
import io.github.core607.poketto.content.RepositoryFilenameSearch;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitRepositoryFilenameSearch {
    private JGitRepositoryFilenameSearch() {}

    static RepositoryFilenamePage search(
            Repository repository, Optional<String> commit, RepositoryFilenameSearch search, Predicate<String> eligible)
            throws IOException {
        if (commit.isEmpty()) {
            return new RepositoryFilenamePage(null, List.of(), 0, search.offset(), search.limit());
        }
        var paths = new ArrayList<String>();
        var matches = new TreeSet<String>();
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk entries = new TreeWalk(repository)) {
            ObjectId tree = revisions
                    .parseCommit(ObjectId.fromString(commit.orElseThrow()))
                    .getTree();
            entries.addTree(tree);
            int visited = 0;
            while (entries.next()) {
                if (++visited > RepositoryFilenameSearch.MAX_ENTRIES) {
                    throw new ContentRepositoryException("Filename search exceeds the repository entry bound");
                }
                if (entries.getPathLength() > ContentLimits.MAX_PATH_LENGTH * 4) {
                    throw new ContentRepositoryException("Filename search entry exceeds the repository path bound");
                }
                String path = entries.getPathString();
                if (FileMode.TREE.equals(entries.getFileMode(0))) {
                    entries.enterSubtree();
                    continue;
                }
                paths.add(path);
                if (RepositoryBlobs.isFile(entries.getFileMode(0))) {
                    match(matches, path, search, eligible);
                }
            }
            var media = JGitRepositoryContentReader.readMediaIndex(repository, tree);
            if (visited + media.files().size() > RepositoryFilenameSearch.MAX_ENTRIES) {
                throw new ContentRepositoryException("Filename search exceeds the combined entry bound");
            }
            try {
                media.requireNoGitCollisions(paths);
            } catch (IllegalArgumentException exception) {
                throw new ContentRepositoryException("Repository media paths collide with Git entries", exception);
            }
            media.files().keySet().forEach(path -> match(matches, path, search, eligible));
        }
        return new RepositoryFilenamePage(
                commit.orElseThrow(),
                matches.stream().skip(search.offset()).limit(search.limit()).toList(),
                matches.size(),
                search.offset(),
                search.limit());
    }

    private static void match(
            TreeSet<String> matches, String path, RepositoryFilenameSearch search, Predicate<String> eligible) {
        if (!eligible.test(path) || !path.contains(search.query())) {
            return;
        }
        try {
            RepositoryPathRules.validate(path);
        } catch (IllegalArgumentException invalidPath) {
            return;
        }
        matches.add(path);
    }
}
