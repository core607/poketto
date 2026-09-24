package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicRevisionHistory;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * A commit contributes only while the policy is enabled, permits the path, and the file parses to
 * the same route and is already due. Collisions with other files are not checked: they are not the
 * author's choice about visibility, and checking them would parse every public file per commit.
 */
final class JGitPublicRevisionHistory implements PublicRevisionHistory {
    static final int MAX_COMMITS = 256;
    static final int MAX_VERSIONS = 50;
    static final long MAX_BODY_CHARS = 4L * 1024 * 1024;
    private static final int MAX_COMMIT_BYTES = 1024 * 1024;
    private static final long MAX_READ_NANOS = Duration.ofSeconds(2).toNanos();
    private static final RepositoryMarkdownParser PARSER = new RepositoryMarkdownParser();
    private final RepositoryAuthority authority;
    private final Clock clock;

    JGitPublicRevisionHistory(RepositoryAuthority authority, Clock clock) {
        this.authority = authority;
        this.clock = clock;
    }

    @Override
    public Revisions read(WorkspaceId workspace, String commit, String path, String route) {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + MAX_READ_NANOS;
        return authority.readImmutableObjects(workspace, objects -> {
            try {
                return walk(objects, ObjectId.fromString(commit), path, route, now, deadline);
            } catch (IOException exception) {
                throw new ContentRepositoryException("public revision history cannot be read", exception);
            }
        });
    }

    static Revisions walk(ObjectReader objects, ObjectId start, String path, String route, Instant now, long deadline)
            throws IOException {
        List<Revision> found = new ArrayList<>();
        ObjectId seen = null;
        long chars = 0;
        try (RevWalk walk = new RevWalk(objects)) {
            walk.setRetainBody(false);
            RevCommit current = commit(objects, walk, start);
            for (int scanned = 0; current != null; scanned++) {
                if (scanned >= MAX_COMMITS || System.nanoTime() >= deadline) {
                    return new Revisions(found, false);
                }
                Optional<ObjectId> blob = admitted(objects, current, path);
                if (blob.isEmpty()) {
                    break;
                }
                Instant savedAt = Instant.ofEpochSecond(current.getCommitTime());
                Optional<String> body = blob.get().equals(seen)
                        ? Optional.of(found.getLast().body())
                        : body(objects, blob.get(), path, route, now);
                if (body.isEmpty()) {
                    break;
                }
                seen = blob.get();
                if (!found.isEmpty() && found.getLast().body().equals(body.get())) {
                    found.set(found.size() - 1, new Revision(savedAt, body.get()));
                } else if (found.size() == MAX_VERSIONS || chars + body.get().length() > MAX_BODY_CHARS) {
                    return new Revisions(found, false);
                } else {
                    chars += body.get().length();
                    found.add(new Revision(savedAt, body.get()));
                }
                current = current.getParentCount() == 0 ? null : commit(objects, walk, current.getParent(0));
            }
        }
        return new Revisions(found, true);
    }

    private static Optional<ObjectId> admitted(ObjectReader objects, RevCommit commit, String path) throws IOException {
        RepositoryPublishingPolicy policy = JGitPublicContentSnapshots.policy(objects, commit.name());
        if (!policy.permitsPath(path)) {
            return Optional.empty();
        }
        try (TreeWalk entry = TreeWalk.forPath(objects, path, commit.getTree())) {
            return entry == null || !RepositoryBlobs.isFile(entry.getFileMode(0))
                    ? Optional.empty()
                    : Optional.of(entry.getObjectId(0));
        }
    }

    private static Optional<String> body(ObjectReader objects, ObjectId blob, String path, String route, Instant now)
            throws IOException {
        var loader = objects.open(blob, Constants.OBJ_BLOB);
        if (loader.getSize() > ContentLimits.MAX_DOCUMENT_BYTES) {
            return Optional.empty();
        }
        try {
            var metadata = PARSER.parse(
                    path, RepositoryMarkdownParser.decode(loader.getBytes(ContentLimits.MAX_DOCUMENT_BYTES)));
            boolean due = metadata.release(path).filter(now::isBefore).isEmpty();
            return metadata.route().equals(route) && due ? Optional.of(metadata.body()) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static RevCommit commit(ObjectReader objects, RevWalk walk, ObjectId id) throws IOException {
        if (objects.getObjectSize(id, Constants.OBJ_COMMIT) > MAX_COMMIT_BYTES) {
            throw new ContentRepositoryException("public revision history commit exceeds its byte limit");
        }
        return walk.parseCommit(id);
    }
}
