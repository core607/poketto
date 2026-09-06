package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitRepositoryBlobReader implements RepositoryBlobReader {
    private final RepositoryAuthority authority;

    JGitRepositoryBlobReader(RepositoryAuthority authority) {
        this.authority = authority;
    }

    @Override
    public Optional<String> selectCommit(WorkspaceId workspace, Optional<String> requested) {
        requested.ifPresent(JGitRepositoryBlobReader::validateCommit);
        return authority.readObjects(workspace, cache -> {
            if (requested.isEmpty()) return cache.commitId();
            if (cache.commitId().isEmpty()) throw unavailable();
            try (Repository repository = JGitContentRepositoryStore.openCache(cache.worktree(), workspace);
                    RevWalk walk = new RevWalk(repository)) {
                walk.markStart(
                        walk.parseCommit(ObjectId.fromString(cache.commitId().orElseThrow())));
                int visited = 0;
                for (var commit : walk) {
                    if (++visited > 100_000) throw unavailable();
                    if (commit.name().equals(requested.orElseThrow())) return requested;
                }
                throw unavailable();
            } catch (IOException exception) {
                throw unavailable();
            }
        });
    }

    @Override
    public Optional<RepositoryBlob> find(WorkspaceId workspace, String commit, String path) {
        validateCommit(commit);
        RepositoryPathRules.validate(path);
        if (RepositoryPathRules.reserved(path)) return Optional.empty();
        return authority.readImmutableObjects(workspace, objects -> {
            try {
                RepositoryPublishingPolicy policy = JGitPublicContentSnapshots.policy(objects, commit);
                return find(objects, workspace, commit, path, policy);
            } catch (IOException exception) {
                throw unavailable();
            }
        });
    }

    @Override
    public List<RepositoryBlob> siblings(
            WorkspaceId workspace, String commit, String documentPath, int limit, boolean publicOnly) {
        validateCommit(commit);
        RepositoryPathRules.validate(documentPath);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("image sibling limit must be 1 to 128");
        String folder = documentPath.contains("/") ? documentPath.substring(0, documentPath.lastIndexOf('/') + 1) : "";
        return scanImages(workspace, commit, folder, true, limit, publicOnly);
    }

    @Override
    public List<RepositoryBlob> images(WorkspaceId workspace, String commit, String prefix) {
        validateCommit(commit);
        if (prefix == null) throw new IllegalArgumentException("image prefix is required");
        if (!prefix.isEmpty())
            RepositoryPathRules.validate(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        return scanImages(workspace, commit, prefix, false, 1000, false);
    }

    private List<RepositoryBlob> scanImages(
            WorkspaceId workspace, String commit, String folder, boolean siblings, int limit, boolean publicOnly) {
        return authority.readImmutableObjects(workspace, objects -> {
            try (RevWalk commits = new RevWalk(objects);
                    TreeWalk tree = new TreeWalk(objects)) {
                tree.addTree(commits.parseCommit(ObjectId.fromString(commit)).getTree());
                tree.setRecursive(true);
                RepositoryPublishingPolicy policy = JGitPublicContentSnapshots.policy(objects, commit);
                List<RepositoryBlob> result = new ArrayList<>();
                int visited = 0;
                while (tree.next()) {
                    if (++visited > 100_000) throw unavailable();
                    String path = tree.getPathString();
                    if (!path.startsWith(folder)
                            || (siblings && path.substring(folder.length()).contains("/"))
                            || !path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)")) continue;
                    if (!regular(tree.getFileMode(0))) continue;
                    long size = tree.getObjectReader().getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
                    if (size > MAX_BLOB_BYTES) continue;
                    try {
                        RepositoryPathRules.validate(path);
                    } catch (IllegalArgumentException invalid) {
                        continue;
                    }
                    if (RepositoryPathRules.reserved(path)) continue;
                    if (publicOnly && !policy.permitsPath(path)) continue;
                    if (result.size() >= limit) throw unavailable();
                    result.add(new RepositoryBlob(
                            workspace, commit, path, tree.getObjectId(0).name(), size, policy.permitsPath(path)));
                }
                result.sort(Comparator.comparing(RepositoryBlob::path));
                return List.copyOf(result);
            } catch (IOException exception) {
                throw unavailable();
            }
        });
    }

    @Override
    public byte[] read(RepositoryBlob descriptor) {
        RepositoryPathRules.validate(descriptor.path());
        return authority.readImmutableObjects(descriptor.workspaceId(), objects -> {
            try (RevWalk commits = new RevWalk(objects);
                    TreeWalk entry = TreeWalk.forPath(
                            objects,
                            descriptor.path(),
                            commits.parseCommit(ObjectId.fromString(descriptor.commit()))
                                    .getTree())) {
                if (entry == null
                        || !regular(entry.getFileMode(0))
                        || !entry.getObjectId(0).name().equals(descriptor.objectId())) throw unavailable();
                var loader = objects.open(entry.getObjectId(0), Constants.OBJ_BLOB);
                if (loader.getSize() != descriptor.size() || loader.getSize() > MAX_BLOB_BYTES) throw unavailable();
                return loader.getBytes(MAX_BLOB_BYTES);
            } catch (IOException exception) {
                throw unavailable();
            }
        });
    }

    private static Optional<RepositoryBlob> find(
            ObjectReader objects, WorkspaceId workspace, String commit, String path, RepositoryPublishingPolicy policy)
            throws IOException {
        try (RevWalk commits = new RevWalk(objects);
                TreeWalk entry = TreeWalk.forPath(
                        objects,
                        path,
                        commits.parseCommit(ObjectId.fromString(commit)).getTree())) {
            if (entry == null || !regular(entry.getFileMode(0))) return Optional.empty();
            long size = objects.getObjectSize(entry.getObjectId(0), Constants.OBJ_BLOB);
            if (size > MAX_BLOB_BYTES) return Optional.empty();
            return Optional.of(new RepositoryBlob(
                    workspace, commit, path, entry.getObjectId(0).name(), size, policy.permitsPath(path)));
        }
    }

    private static boolean regular(FileMode mode) {
        return FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode);
    }

    private static void validateCommit(String commit) {
        if (commit == null || !commit.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("commit must be an exact object id");
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("repository image is unavailable");
    }
}
