package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Walks a commit's eligible Markdown within the entry, document and text byte bounds, and parses
 * each document.
 *
 * <p>A scan that keeps documents reuses the previous kept scan of the same workspace. A document's
 * file and metadata depend only on its path, mode, blob and scope, so a document unchanged in
 * those four is taken from that scan, rebased onto the new commit, instead of being read and
 * parsed again. Each kept scan replaces its workspace's documents with the ones it saw, so the
 * cache holds at most one scan's documents per workspace. Only the public snapshot keeps scans:
 * other callers read different paths, and would only displace its documents.
 */
final class RepositoryDocumentScanner {
    /** Tree entries one scan visits, from the repository authoring record. */
    static final int MAX_TREE_ENTRIES = 100_000;

    private final RepositoryMarkdownParser parser = new RepositoryMarkdownParser();
    private final Map<WorkspaceId, Map<Key, Parsed>> kept = new ConcurrentHashMap<>();

    /** Every eligible Markdown path, the documents that parsed, and the diagnostics collected so far. */
    record Scan(List<String> paths, List<Parsed> parsed, List<RepositoryDiagnostic> diagnostics) {}

    record Parsed(RepositoryFile file, RepositoryMarkdownParser.Metadata metadata) {}

    private record Key(String path, int mode, ObjectId blob, boolean publicScope) {}

    void forget(WorkspaceId workspaceId) {
        kept.remove(workspaceId);
    }

    /** Callers serialize scans of one workspace, as the authority's workspace lock does for snapshots. */
    Scan scan(
            WorkspaceId workspaceId,
            Repository repository,
            Optional<String> resolved,
            Predicate<String> eligible,
            boolean keep)
            throws IOException {
        var policy = PublicRepositoryDirectories.publicPolicy(repository, resolved);
        Map<Key, Parsed> previous = keep ? kept.getOrDefault(workspaceId, Map.of()) : Map.of();
        Map<Key, Parsed> seen = new HashMap<>();
        List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        long total = 0;
        int entries = 0;
        try (RevWalk revisions = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(revisions
                    .parseCommit(ObjectId.fromString(resolved.orElseThrow()))
                    .getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                if (++entries > MAX_TREE_ENTRIES) {
                    throw new ContentRepositoryException("repository tree entry limit exceeded");
                }
                String path = tree.getPathString();
                if (!RepositoryPathRules.markdown(path) || RepositoryPathRules.reserved(path) || !eligible.test(path)) {
                    continue;
                }
                if (paths.size() >= ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) {
                    throw new ContentRepositoryException("repository Markdown count limit exceeded");
                }
                paths.add(path);
                FileMode mode = tree.getFileMode(0);
                if (RepositoryBlobs.isFile(mode)) {
                    long size = tree.getObjectReader().getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                    if (size <= ContentLimits.MAX_DOCUMENT_BYTES) {
                        total += size;
                        if (total > ContentLimits.MAX_WORKSPACE_BYTES) {
                            throw new ContentRepositoryException("repository text byte limit exceeded");
                        }
                    }
                }
                var key = new Key(path, tree.getRawMode(0), tree.getObjectId(0), policy.permitsPath(path));
                Parsed reused = previous.get(key);
                if (reused != null) {
                    seen.put(key, reused);
                    parsed.add(rebased(reused, resolved));
                    continue;
                }
                Parsed document = read(repository, workspaceId, resolved, key, diagnostics);
                if (document != null) {
                    seen.put(key, document);
                    parsed.add(document);
                }
            }
        }
        if (keep) {
            kept.put(workspaceId, Map.copyOf(seen));
        }
        return new Scan(paths, parsed, diagnostics);
    }

    /** The document at this entry, or null with its diagnostics recorded when it cannot be used. */
    private Parsed read(
            Repository repository,
            WorkspaceId workspaceId,
            Optional<String> resolved,
            Key key,
            List<RepositoryDiagnostic> diagnostics)
            throws IOException {
        RepositoryFile file =
                JGitRepositoryFileReader.read(repository, workspaceId, resolved, key.path(), key.publicScope());
        diagnostics.addAll(file.diagnostics());
        if (file.source().isEmpty() || !file.diagnostics().isEmpty()) {
            return null;
        }
        try {
            return new Parsed(file, parser.parse(key.path(), file.source().orElseThrow()));
        } catch (IllegalArgumentException exception) {
            diagnostics.add(new RepositoryDiagnostic(key.path(), "INVALID_MARKDOWN", exception.getMessage()));
            return null;
        }
    }

    private static Parsed rebased(Parsed document, Optional<String> commit) {
        RepositoryFile file = document.file();
        return new Parsed(
                new RepositoryFile(
                        file.workspaceId(),
                        commit,
                        file.path(),
                        file.expectedAbsence(),
                        file.source(),
                        file.revision(),
                        file.diagnostics(),
                        file.publicScope()),
                document.metadata());
    }
}
