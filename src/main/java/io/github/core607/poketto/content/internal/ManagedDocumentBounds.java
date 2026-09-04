package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import java.io.IOException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Checks managed blob headers before a tree is checked out or loaded into a snapshot. */
final class ManagedDocumentBounds {

    private ManagedDocumentBounds() {}

    static void check(Repository repository, ObjectId commit) {
        if (commit.equals(ObjectId.zeroId())) {
            return;
        }
        try (TreeWalk walk = new TreeWalk(repository)) {
            ObjectId tree = repository.resolve(commit.name() + "^{tree}");
            if (tree == null) {
                throw new ContentRepositoryException("resolved main does not name a commit tree");
            }
            walk.addTree(tree);
            walk.setRecursive(true);
            int count = 0;
            long totalBytes = 0;
            while (walk.next()) {
                String path = walk.getPathString();
                if (!path.equals("documents") && !path.startsWith("documents/")) {
                    continue;
                }
                if (!(FileMode.REGULAR_FILE.equals(walk.getFileMode(0))
                        || FileMode.EXECUTABLE_FILE.equals(walk.getFileMode(0)))) {
                    throw new ContentRepositoryException("non-file managed document at " + path);
                }
                if (++count > ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) {
                    throw new ContentRepositoryException(
                            "managed documents exceed " + ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE + " at " + path);
                }
                long size = walk.getObjectReader().getObjectSize(walk.getObjectId(0), Constants.OBJ_BLOB);
                if (size > ContentLimits.MAX_DOCUMENT_BYTES) {
                    throw new ContentRepositoryException("invalid document " + path + ": document must not exceed "
                            + ContentLimits.MAX_DOCUMENT_BYTES + " bytes: " + size);
                }
                totalBytes += size;
                if (totalBytes > ContentLimits.MAX_WORKSPACE_BYTES) {
                    throw new ContentRepositoryException(
                            "managed documents exceed " + ContentLimits.MAX_WORKSPACE_BYTES + " bytes at " + path);
                }
            }
        } catch (IOException exception) {
            throw new ContentRepositoryException("managed document bounds cannot be checked", exception);
        }
    }
}
