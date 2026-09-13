package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryDirectoryPage;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class PublicRepositoryDirectories {
    private static final int MAX_TREE_ENTRIES = 100_000;

    private PublicRepositoryDirectories() {}

    private record Directory(Map<String, RepositoryDirectoryPage.Entry> children, boolean exists) {}

    static RepositoryDirectoryPage page(
            Repository repository, WorkspaceId workspace, Optional<String> resolved, String path, int offset, int limit)
            throws IOException {
        var policy = publicPolicy(repository, resolved);
        if (!path.isEmpty() && !path.equals("public") && !policy.permitsPath(path)) {
            throw denied();
        }
        if (resolved.isEmpty()) {
            return new RepositoryDirectoryPage(workspace, resolved, path, !path.isEmpty(), List.of(), null);
        }
        Directory directory = scan(repository, resolved.orElseThrow(), path, policy);
        Map<String, RepositoryDirectoryPage.Entry> children = directory.children();
        boolean exists = directory.exists();
        List<RepositoryDirectoryPage.Entry> ordered = children.values().stream()
                .sorted(Comparator.comparing(RepositoryDirectoryPage.Entry::path))
                .toList();
        return new RepositoryDirectoryPage(
                workspace,
                resolved,
                path,
                !exists,
                ordered.stream().skip(offset).limit(limit).toList(),
                offset + limit < ordered.size() ? offset + limit : null);
    }

    private static Directory scan(Repository repository, String commit, String path, RepositoryPublishingPolicy policy)
            throws IOException {
        Map<String, RepositoryDirectoryPage.Entry> children = new HashMap<>();
        boolean exists = path.isEmpty();
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk entries = new TreeWalk(repository)) {
            ObjectId tree = revisions.parseCommit(ObjectId.fromString(commit)).getTree();
            if (!path.isEmpty()) {
                try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
                    if (entry != null) {
                        if (!FileMode.TREE.equals(entry.getFileMode(0))) {
                            throw new IllegalArgumentException("requested path is not a directory");
                        }
                        exists = true;
                    }
                }
            }
            entries.addTree(tree);
            entries.setRecursive(true);
            int count = 0;
            while (entries.next()) {
                if (++count > MAX_TREE_ENTRIES) {
                    throw new ContentRepositoryException("repository tree entry limit exceeded");
                }
                String candidate = entries.getPathString();
                if (!policy.permitsPath(candidate) || !RepositoryBlobs.isFile(entries.getFileMode(0))) {
                    continue;
                }
                JGitRepositoryContentReader.addImmediate(children, path, candidate, RepositoryDirectoryPage.Kind.FILE);
            }
            var media = JGitRepositoryContentReader.readMediaIndex(repository, tree);
            for (String candidate : media.files().keySet()) {
                if (!policy.permitsPath(candidate)) {
                    continue;
                }
                if (candidate.equals(path)) {
                    throw new IllegalArgumentException("requested path is not a directory");
                }
                JGitRepositoryContentReader.addImmediate(children, path, candidate, RepositoryDirectoryPage.Kind.FILE);
                if (candidate.startsWith(path + "/")) {
                    exists = true;
                }
            }
        }
        return new Directory(children, exists);
    }

    private static AuthException denied() {
        return new AuthException(AuthException.Code.DENIED);
    }

    static RepositoryPublishingPolicy publicPolicy(Repository repository, Optional<String> commit) {
        if (commit.isEmpty()) {
            return RepositoryPublishingPolicy.missing();
        }
        try (var objects = repository.newObjectReader()) {
            return JGitPublicContentSnapshots.policy(objects, commit.orElseThrow());
        }
    }
}
