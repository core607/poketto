package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Shared immutable-file decoding for individual queries and private baseline traversal. */
final class JGitRepositoryFileReader {
    private JGitRepositoryFileReader() {}

    static RepositoryFile read(
            Repository repository, WorkspaceId workspace, Optional<String> commit, String path, boolean publicScope)
            throws IOException {
        try {
            RepositoryPathRules.validate(path);
        } catch (IllegalArgumentException exception) {
            return invalid(workspace, commit, path, "INVALID_PATH", exception.getMessage(), publicScope);
        }
        if (commit.isEmpty()) {
            return absent(workspace, commit, path, publicScope);
        }
        try (var revisions = new RevWalk(repository)) {
            ObjectId tree = revisions
                    .parseCommit(ObjectId.fromString(commit.orElseThrow()))
                    .getTree();
            try (TreeWalk entry = TreeWalk.forPath(repository, path, tree)) {
                if (entry == null) {
                    return JGitRepositoryContentReader.readMediaIndex(repository, tree)
                                    .files()
                                    .containsKey(path)
                            ? managed(workspace, commit, path, publicScope)
                            : absent(workspace, commit, path, publicScope);
                }
                if (!RepositoryBlobs.isFile(entry.getFileMode(0))) {
                    return invalid(
                            workspace, commit, path, "NOT_REGULAR_FILE", "path is not a regular file", publicScope);
                }
                ObjectLoader loader = repository.open(entry.getObjectId(0), Constants.OBJ_BLOB);
                if (loader.getSize() > ContentLimits.MAX_DOCUMENT_BYTES) {
                    return invalid(
                            workspace, commit, path, "FILE_TOO_LARGE", "file exceeds the text byte limit", publicScope);
                }
                return decode(workspace, commit, path, publicScope, loader.getBytes(ContentLimits.MAX_DOCUMENT_BYTES));
            }
        }
    }

    private static RepositoryFile decode(
            WorkspaceId workspace, Optional<String> commit, String path, boolean publicScope, byte[] bytes) {
        DocumentRevision revision = DocumentRevision.sha256(bytes);
        try {
            String source = RepositoryMarkdownParser.decode(bytes);
            List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
            if (RepositoryPathRules.markdown(path)) {
                try {
                    new RepositoryMarkdownParser().parse(path, source);
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(new RepositoryDiagnostic(path, "INVALID_MARKDOWN", exception.getMessage()));
                }
            }
            return new RepositoryFile(
                    workspace,
                    commit,
                    path,
                    false,
                    Optional.of(source),
                    Optional.of(revision),
                    diagnostics,
                    publicScope);
        } catch (IllegalArgumentException exception) {
            return new RepositoryFile(
                    workspace,
                    commit,
                    path,
                    false,
                    Optional.empty(),
                    Optional.of(revision),
                    List.of(new RepositoryDiagnostic(path, "INVALID_UTF8", exception.getMessage())),
                    publicScope);
        }
    }

    static RepositoryFile managed(WorkspaceId workspace, Optional<String> commit, String path, boolean publicScope) {
        return invalid(
                workspace,
                commit,
                path,
                "MANAGED_MEDIA",
                "path is an indexed media file; fetch its original through the media entrance",
                publicScope);
    }

    private static RepositoryFile absent(
            WorkspaceId workspace, Optional<String> commit, String path, boolean publicScope) {
        return new RepositoryFile(
                workspace, commit, path, true, Optional.empty(), Optional.empty(), List.of(), publicScope);
    }

    private static RepositoryFile invalid(
            WorkspaceId workspace,
            Optional<String> commit,
            String path,
            String code,
            String message,
            boolean publicScope) {
        return new RepositoryFile(
                workspace,
                commit,
                path,
                false,
                Optional.empty(),
                Optional.empty(),
                List.of(new RepositoryDiagnostic(path, code, message)),
                publicScope);
    }
}
