package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositorySyncEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitRepositorySyncReader {
    private JGitRepositorySyncReader() {}

    static RepositorySyncEntry read(Repository repository, String commit, String path, OutputStream output)
            throws IOException {
        RepositoryPathRules.validate(path);
        try (var revisions = new RevWalk(repository);
                TreeWalk entry = TreeWalk.forPath(
                        repository,
                        path,
                        revisions.parseCommit(ObjectId.fromString(commit)).getTree())) {
            if (entry == null) {
                return new RepositorySyncEntry(commit, path, RepositorySyncEntry.Kind.ABSENT, 0, null);
            }
            if (entry.getFileMode(0).equals(FileMode.TREE)) {
                return new RepositorySyncEntry(commit, path, RepositorySyncEntry.Kind.DIRECTORY, 0, null);
            }
            if (!RepositoryBlobs.isFile(entry.getFileMode(0))) {
                return new RepositorySyncEntry(commit, path, RepositorySyncEntry.Kind.UNSUPPORTED, 0, null);
            }
            return copy(repository, entry.getObjectId(0), commit, path, output);
        }
    }

    private static RepositorySyncEntry copy(
            Repository repository, ObjectId object, String commit, String path, OutputStream output)
            throws IOException {
        var loader = repository.open(object, Constants.OBJ_BLOB);
        if (loader.getSize() > RepositorySyncEntry.MAX_BYTES) {
            return new RepositorySyncEntry(commit, path, RepositorySyncEntry.Kind.UNSUPPORTED, 0, null);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
        long size = 0;
        try (var input = loader.openStream()) {
            byte[] block = new byte[65536];
            int count;
            while ((count = input.read(block)) != -1) {
                size += count;
                if (size > RepositorySyncEntry.MAX_BYTES) {
                    throw new IOException("repository blob exceeds the 128 MiB bound");
                }
                digest.update(block, 0, count);
                output.write(block, 0, count);
            }
        }
        if (size != loader.getSize()) {
            throw new IOException("repository blob length changed");
        }
        return new RepositorySyncEntry(
                commit,
                path,
                RepositorySyncEntry.Kind.FILE,
                size,
                HexFormat.of().formatHex(digest.digest()));
    }
}
