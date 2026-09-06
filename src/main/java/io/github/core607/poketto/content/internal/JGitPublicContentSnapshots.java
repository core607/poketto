package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

final class JGitPublicContentSnapshots implements PublicContentSnapshots {
    private final RepositoryAuthority authority;
    private final JGitRepositoryContentReader reader;
    private final Clock clock;
    private final Duration lifetime;
    private final PublicSnapshotMarker marker;
    private final Map<WorkspaceId, PublicContentSnapshot> snapshots = new ConcurrentHashMap<>();

    JGitPublicContentSnapshots(RepositoryAuthority authority, Clock clock, Duration lifetime) {
        this(authority, clock, lifetime, new PublicSnapshotMarker());
    }

    JGitPublicContentSnapshots(
            RepositoryAuthority authority, Clock clock, Duration lifetime, PublicSnapshotMarker marker) {
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("public snapshot lifetime must be positive and at most one hour");
        this.authority = authority;
        this.reader = new JGitRepositoryContentReader(authority);
        this.clock = clock;
        this.lifetime = lifetime;
        this.marker = marker;
    }

    @Override
    public void ensureReady(WorkspaceId workspaceId) {
        try {
            refresh(workspaceId);
        } catch (ContentRepositoryException unavailable) {
            if (unavailable.getCause() instanceof PublicSnapshotMarker.WriteFailure) throw unavailable;
            try {
                authority.readCache(workspaceId, snapshot -> restore(workspaceId, snapshot));
                current(workspaceId);
            } catch (ContentRepositoryException restoreFailed) {
                unavailable.addSuppressed(restoreFailed);
                throw unavailable;
            }
        }
    }

    @Override
    public PublicContentSnapshot refresh(WorkspaceId workspaceId) {
        return authority.readObjects(workspaceId, snapshot -> installAcknowledged(workspaceId, snapshot));
    }

    /** Caller holds the workspace authority lock after an acknowledged ref update. Never fetches. */
    PublicContentSnapshot installAcknowledged(WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot) {
        Instant verifiedAt = clock.instant();
        PublicContentSnapshot previous = snapshots.get(workspaceId);
        if (previous != null && previous.commit().equals(snapshot.commitId())) {
            PublicContentSnapshot renewed = new PublicContentSnapshot(
                    workspaceId, snapshot.commitId(), verifiedAt, verifiedAt.plus(lifetime), previous.articles());
            writeMarker(workspaceId, snapshot, verifiedAt, true);
            snapshots.put(workspaceId, renewed);
            return renewed;
        }
        // Recording CLOSED precedes parsing. A crash or a bad policy cannot resurrect the earlier
        // publication decision. Offline restoration also requires the marker and cache main to match.
        snapshots.remove(workspaceId);
        writeMarker(workspaceId, snapshot, verifiedAt, false);
        PublicContentSnapshot result = build(workspaceId, snapshot, verifiedAt);
        writeMarker(workspaceId, snapshot, verifiedAt, true);
        snapshots.put(workspaceId, result);
        return result;
    }

    /** Caller holds the workspace authority lock before submitting a publication-affecting write. */
    void closePublication(WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot) {
        snapshots.remove(workspaceId);
        writeMarker(workspaceId, snapshot, clock.instant(), false);
    }

    @Override
    public PublicContentSnapshot current(WorkspaceId workspaceId) {
        PublicContentSnapshot snapshot = snapshots.get(workspaceId);
        Instant now = clock.instant();
        if (snapshot == null || now.isBefore(snapshot.verifiedAt()) || !now.isBefore(snapshot.expiresAt()))
            throw unavailable();
        return snapshot;
    }

    @Override
    public <T> T withCurrent(WorkspaceId workspaceId, java.util.function.Function<PublicContentSnapshot, T> action) {
        return authority.readCache(workspaceId, ignored -> action.apply(current(workspaceId)));
    }

    private PublicContentSnapshot restore(WorkspaceId workspaceId, RepositoryAuthority.Snapshot cache) {
        if (!marker.supportsOfflineRestoration())
            throw new ContentRepositoryException(
                    "offline public snapshot restoration is disabled on Windows development hosts");
        try (Repository repository = JGitContentRepositoryStore.openCache(cache.worktree(), workspaceId)) {
            Path marker = repository.getDirectory().toPath().resolve(PublicSnapshotMarker.NAME);
            if (!Files.isRegularFile(marker) || Files.size(marker) > 256) throw unavailable();
            String[] fields =
                    Files.readString(marker, StandardCharsets.UTF_8).strip().split(" ", -1);
            if (fields.length != 4
                    || !fields[0].equals("DURABLE_V2")
                    || !fields[3].equals("OPEN")
                    || !fields[1].equals(cache.commitId().orElse("unborn"))) throw unavailable();
            Instant verifiedAt = Instant.parse(fields[2]);
            if (clock.instant().isBefore(verifiedAt) || !clock.instant().isBefore(verifiedAt.plus(lifetime)))
                throw unavailable();
            PublicContentSnapshot restored = build(workspaceId, cache, verifiedAt);
            snapshots.put(workspaceId, restored);
            return restored;
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw new ContentRepositoryException("public snapshot cache cannot be restored", exception);
        }
    }

    private PublicContentSnapshot build(
            WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot, Instant verifiedAt) {
        RepositoryPublishingPolicy policy = policy(workspaceId, snapshot);
        if (policy.state() == RepositoryPublishingPolicy.State.INVALID) throw unavailable();
        List<PublicArticle> articles = List.of();
        if (policy.state() == RepositoryPublishingPolicy.State.ENABLED) {
            articles = reader.readSnapshot(workspaceId, snapshot, policy::permitsPath).documents().stream()
                    .map(document -> new PublicArticle(
                            document.file().path(),
                            document.route(),
                            document.title(),
                            document.body(),
                            document.tags(),
                            document.createdAt(),
                            document.updatedAt(),
                            document.folderPage()))
                    .sorted(Comparator.comparing(PublicArticle::createdAt)
                            .reversed()
                            .thenComparing(PublicArticle::route))
                    .toList();
        }
        return new PublicContentSnapshot(
                workspaceId, snapshot.commitId(), verifiedAt, verifiedAt.plus(lifetime), articles);
    }

    static RepositoryPublishingPolicy policy(WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot) {
        if (snapshot.commitId().isEmpty()) return RepositoryPublishingPolicy.missing();
        try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspaceId);
                ObjectReader objects = repository.newObjectReader()) {
            return policy(objects, snapshot.commitId().orElseThrow());
        }
    }

    static RepositoryPublishingPolicy policy(ObjectReader objects, String commit) {
        try (RevWalk commits = new RevWalk(objects);
                TreeWalk entry = TreeWalk.forPath(
                        objects,
                        RepositoryPublishingPolicy.PATH,
                        commits.parseCommit(ObjectId.fromString(commit)).getTree())) {
            if (entry == null) return RepositoryPublishingPolicy.missing();
            if (!FileMode.REGULAR_FILE.equals(entry.getFileMode(0))
                    && !FileMode.EXECUTABLE_FILE.equals(entry.getFileMode(0)))
                return RepositoryPublishingPolicy.parse(null);
            var loader = objects.open(entry.getObjectId(0), Constants.OBJ_BLOB);
            if (loader.getSize() > RepositoryPublishingPolicy.MAX_BYTES) return RepositoryPublishingPolicy.parse(null);
            return RepositoryPublishingPolicy.parse(loader.getBytes(RepositoryPublishingPolicy.MAX_BYTES));
        } catch (IOException exception) {
            throw new ContentRepositoryException("publishing policy object cannot be read", exception);
        }
    }

    private void writeMarker(WorkspaceId workspaceId, RepositoryAuthority.Snapshot snapshot, Instant at, boolean open) {
        try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspaceId)) {
            marker.write(repository.getDirectory().toPath(), snapshot.commitId().orElse("unborn"), at, open);
        } catch (IOException | UnsupportedOperationException exception) {
            snapshots.remove(workspaceId);
            throw new ContentRepositoryException("public snapshot state cannot be recorded", exception);
        }
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("public content snapshot is unavailable");
    }
}
