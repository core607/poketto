package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Streams one authoritative object snapshot; it never refreshes or resolves history per file. */
final class JGitRepositoryBaselineReader {
    private final ObjectReader objects;
    private final WorkspaceId workspace;
    private final Optional<String> commit;
    private final RepositoryBaselineLimits limits;
    private final long deadline;
    private final Consumer<RepositoryFile> sink;
    private final Set<String> visited = new HashSet<>();
    private long textBytes;

    JGitRepositoryBaselineReader(
            ObjectReader objects,
            WorkspaceId workspace,
            String commit,
            RepositoryBaselineLimits limits,
            long deadline,
            Consumer<RepositoryFile> sink) {
        this.objects = objects;
        this.workspace = workspace;
        this.commit = Optional.of(commit);
        this.limits = limits;
        this.deadline = deadline;
        this.sink = sink;
    }

    void visit() throws IOException {
        checkDeadline();
        var policy = JGitPublicContentSnapshots.policy(objects, commit.orElseThrow());
        try (var revisions = new RevWalk(objects);
                var entries = new TreeWalk(objects)) {
            ObjectId tree = revisions
                    .parseCommit(ObjectId.fromString(commit.orElseThrow()))
                    .getTree();
            entries.addTree(tree);
            while (entries.next()) {
                if (entries.getPathLength() > ContentLimits.MAX_PATH_LENGTH * 4) {
                    throw new ContentRepositoryException("repository baseline path byte limit exceeded");
                }
                String path = StrictText.utf8(entries.getRawPath());
                reserve(path);
                emit(JGitRepositoryFileReader.readEntry(
                        objects,
                        workspace,
                        commit,
                        path,
                        entries.getFileMode(0),
                        entries.getObjectId(0),
                        policy.permitsPath(path)));
                if (entries.isSubtree()) {
                    entries.enterSubtree();
                }
            }
            var media = JGitRepositoryContentReader.readMediaIndex(objects, tree);
            for (String path : media.files().keySet()) {
                if (!visited.contains(path)) {
                    reserve(path);
                    emit(JGitRepositoryFileReader.managed(workspace, commit, path, policy.permitsPath(path)));
                }
            }
        }
        checkDeadline();
    }

    private void reserve(String path) {
        checkDeadline();
        RepositoryPathRules.validate(path);
        if (visited.size() >= limits.entries()) {
            throw new ContentRepositoryException("repository baseline entry limit exceeded");
        }
        if (!visited.add(path)) {
            throw new ContentRepositoryException("repository baseline contains a duplicate path");
        }
    }

    private void emit(RepositoryFile file) {
        long bytes = file.source()
                .map(value -> (long) value.getBytes(StandardCharsets.UTF_8).length)
                .orElse(0L);
        if (bytes > limits.textBytes() - textBytes) {
            throw new ContentRepositoryException("repository baseline text byte limit exceeded");
        }
        textBytes += bytes;
        checkDeadline();
        sink.accept(file);
        checkDeadline();
    }

    private void checkDeadline() {
        if (System.nanoTime() - deadline >= 0) {
            throw new ContentRepositoryException("repository baseline traversal deadline exceeded");
        }
    }
}
