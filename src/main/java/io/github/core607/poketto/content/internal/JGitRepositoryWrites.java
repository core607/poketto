package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.content.RepositoryWriteCheckpoint;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One authorized write of a prepared candidate against the repository authority: the candidate
 * tree is built on the checked base, publication authority is decided from what the edits touch,
 * the commit is retained before the remote ref advances, and a retained attempt is reconciled
 * instead of pushed twice. Remote acknowledgement followed by a local failure is reported as an
 * ambiguous write that names the attempt.
 */
final class JGitRepositoryWrites {
    private static final Logger log = LoggerFactory.getLogger(JGitRepositoryWrites.class);
    /** Tree entries one scan visits, from the repository authoring record. */
    private static final int MAX_TREE_ENTRIES = 100_000;

    private final RepositoryAuthority authority;
    private final AuthService auth;
    private final Clock clock;
    private final BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installAcknowledged;
    private final BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> closePublication;
    private final RepositoryMediaValidator mediaValidator;

    JGitRepositoryWrites(
            RepositoryAuthority authority,
            AuthService auth,
            Clock clock,
            BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installAcknowledged,
            BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> closePublication,
            RepositoryMediaValidator mediaValidator) {
        this.authority = authority;
        this.auth = auth;
        this.clock = clock;
        this.installAcknowledged = installAcknowledged;
        this.closePublication = closePublication;
        this.mediaValidator = Objects.requireNonNull(mediaValidator);
    }

    /** Prepares the candidate's changes against the base index; it may read the base repository. */
    @FunctionalInterface
    interface Preparer {
        RepositoryCandidateChanges prepare(Repository repository, DirCache index) throws IOException;
    }

    RepositoryPatchResult write(
            AuthPrincipal principal,
            WorkspaceId workspace,
            Optional<String> baseCommit,
            Optional<WritePrincipal> suggestedBy,
            Set<Capability> capabilities,
            Optional<RepositoryWriteAttempt> recovery,
            RepositoryWriteCheckpoint checkpoint,
            Preparer preparer) {
        Objects.requireNonNull(checkpoint, "repository write checkpoint is required");
        var write = new Write(principal, workspace, baseCommit, suggestedBy, recovery, checkpoint, preparer);
        try {
            auth.authorize(principal, workspace, capabilities.toArray(Capability[]::new));
            return authority.withPreparedCredentials(
                    workspace,
                    () -> auth.withAuthorization(
                            principal, workspace, capabilities, () -> authority.writeObjects(workspace, write::run)));
        } catch (RuntimeException exception) {
            throw write.outcome(exception);
        }
    }

    /** One write against one snapshot, carrying whether the remote acknowledged it and which attempt. */
    private final class Write {
        private final AuthPrincipal principal;
        private final WorkspaceId workspace;
        private final Optional<String> baseCommit;
        private final Optional<WritePrincipal> suggestedBy;
        private final Optional<RepositoryWriteAttempt> recovery;
        private final RepositoryWriteCheckpoint checkpoint;
        private final Preparer preparer;
        private boolean acknowledged;
        private RepositoryWriteAttempt attempt;

        Write(
                AuthPrincipal principal,
                WorkspaceId workspace,
                Optional<String> baseCommit,
                Optional<WritePrincipal> suggestedBy,
                Optional<RepositoryWriteAttempt> recovery,
                RepositoryWriteCheckpoint checkpoint,
                Preparer preparer) {
            this.principal = principal;
            this.workspace = workspace;
            this.baseCommit = baseCommit;
            this.suggestedBy = suggestedBy;
            this.recovery = recovery;
            this.checkpoint = checkpoint;
            this.preparer = preparer;
        }

        RepositoryPatchResult run(RepositoryAuthority.Snapshot snapshot, RepositoryAuthority.RefAdvancer advancer) {
            if (recovery.isEmpty() && !snapshot.commitId().equals(baseCommit)) {
                throw new RepositoryConflictException(
                        "repository base commit changed; read current files before retrying");
            }
            try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspace);
                    RevWalk walk = new RevWalk(repository);
                    var reader = repository.newObjectReader();
                    ObjectInserter inserter = repository.newObjectInserter()) {
                ObjectId base = baseCommit.map(ObjectId::fromString).orElse(ObjectId.zeroId());
                requireBoundedTree(repository, base);
                DirCache index = base.equals(ObjectId.zeroId())
                        ? DirCache.newInCore()
                        : DirCache.read(reader, walk.parseCommit(base).getTree());
                RepositoryCandidateChanges changes = preparer.prepare(repository, index);
                Map<String, OriginalEntry> untouched = untouchedEntries(index, changes.paths());
                Publication publication = new Publication(repository, index, changes);
                Map<String, Optional<DocumentRevision>> revisions = edit(index, inserter, changes);
                requireUntouched(index, untouched);
                checkCandidate(index, changes.replacements(), repository, changes.paths());
                Publication.Decision decision = publication.complete(repository, index);
                if (decision.publish()) {
                    auth.authorize(principal, workspace, Capability.PUBLISH);
                }
                publication.validateMedia(workspace, decision.media());
                ObjectId tree = index.writeTree(inserter);
                if (!base.equals(ObjectId.zeroId())
                        && tree.equals(walk.parseCommit(base).getTree().getId())) {
                    if (recovery.isPresent()) {
                        throw new IllegalArgumentException("retained attempt cannot describe an unchanged tree");
                    }
                    return new RepositoryPatchResult(base.name(), false, false, revisions);
                }
                CommitBuilder candidate = candidate(tree, base);
                byte[] commitBytes =
                        recovery.isPresent() ? recovery.orElseThrow().object() : candidate.build();
                ObjectId commit = inserter.insert(Constants.OBJ_COMMIT, commitBytes);
                inserter.flush();
                if (recovery.isPresent()) {
                    Optional<RepositoryPatchResult> reconciled =
                            reconcile(snapshot, walk, commit, tree, base, candidate, revisions);
                    if (reconciled.isPresent()) {
                        return reconciled.orElseThrow();
                    }
                }
                return publish(snapshot, advancer, commit, commitBytes, decision.publish(), revisions);
            } catch (IOException exception) {
                throw new ContentRepositoryException("repository changes could not be prepared", exception);
            }
        }

        private CommitBuilder candidate(ObjectId tree, ObjectId base) {
            CommitBuilder candidate = new CommitBuilder();
            candidate.setTreeId(tree);
            if (!base.equals(ObjectId.zeroId())) {
                candidate.setParentId(base);
            }
            PersonIdent author = new PersonIdent("Poketto", "poketto@invalid", clock.instant(), ZoneOffset.UTC);
            candidate.setAuthor(author);
            candidate.setCommitter(author);
            WritePrincipal attribution = new WritePrincipal(
                    principal.kind() == AuthPrincipal.Kind.ACCOUNT ? PrincipalType.ACCOUNT : PrincipalType.API_KEY,
                    principal.subjectId().toString());
            candidate.setMessage("Apply repository changes\n\nPoketto-Principal: " + attribution.trailerValue() + "\n"
                    + suggestedBy
                            .map(suggester -> "Poketto-Suggested-By: " + suggester.trailerValue() + "\n")
                            .orElse(""));
            return candidate;
        }

        // A retained attempt must be the commit this authorized patch would produce. If remote main
        // already contains it, the current snapshot is installed and nothing is pushed; a remote that
        // moved elsewhere is a conflict, never a duplicate push.
        private Optional<RepositoryPatchResult> reconcile(
                RepositoryAuthority.Snapshot snapshot,
                RevWalk walk,
                ObjectId commit,
                ObjectId tree,
                ObjectId base,
                CommitBuilder candidate,
                Map<String, Optional<DocumentRevision>> revisions)
                throws IOException {
            var retained = recovery.orElseThrow();
            RevCommit parsed = walk.parseCommit(commit);
            requireRetainedMatch(parsed, retained, commit, tree, base, candidate);
            attempt = retained;
            ObjectId current = snapshot.commitId().map(ObjectId::fromString).orElse(ObjectId.zeroId());
            if (!current.equals(ObjectId.zeroId()) && walk.isMergedInto(parsed, walk.parseCommit(current))) {
                // A later remote edit must not be hidden by installing the older candidate.
                acknowledged = true;
                boolean installed = install(snapshot, commit, true);
                return Optional.of(new RepositoryPatchResult(commit.name(), true, installed, revisions));
            }
            if (!snapshot.commitId().equals(baseCommit)) {
                throw new RepositoryConflictException("remote main diverged from the retained write attempt");
            }
            return Optional.empty();
        }

        private RepositoryPatchResult publish(
                RepositoryAuthority.Snapshot snapshot,
                RepositoryAuthority.RefAdvancer advancer,
                ObjectId commit,
                byte[] commitBytes,
                boolean needsPublish,
                Map<String, Optional<DocumentRevision>> revisions) {
            attempt = new RepositoryWriteAttempt(commit.name(), commitBytes);
            checkpoint.retain(attempt);
            // Close the prior authorization before a remote outcome can become uncertain.
            // Failure to persist this marker must prevent the push itself.
            if (needsPublish) {
                closePublication.accept(workspace, snapshot);
            }
            advancer.advance(commit.name());
            acknowledged = true;
            boolean snapshotUpdated = install(
                    new RepositoryAuthority.Snapshot(snapshot.worktree(), Optional.of(commit.name())), commit, false);
            return new RepositoryPatchResult(commit.name(), true, snapshotUpdated, revisions);
        }

        // The snapshot service closes public reads on installation failure. Remote acknowledgement
        // cannot be undone by a derived-view failure.
        private boolean install(RepositoryAuthority.Snapshot snapshot, ObjectId commit, boolean recovered) {
            try {
                installAcknowledged.accept(workspace, snapshot);
                return true;
            } catch (RuntimeException unavailable) {
                log.warn(
                        recovered
                                ? "workspace {} recovered commit {} but current snapshot installation failed"
                                : "workspace {} acknowledged commit {} but public snapshot installation failed",
                        workspace,
                        commit.name(),
                        unavailable);
                return false;
            }
        }

        RuntimeException outcome(RuntimeException exception) {
            if (exception instanceof RepositoryWriteAmbiguousException unknown && attempt != null) {
                return new RepositoryWriteAmbiguousException(unknown.getMessage(), attempt);
            }
            if (acknowledged) {
                return new RepositoryWriteAmbiguousException(
                        "remote acknowledged the patch but local completion failed; read remote main before retrying",
                        attempt);
            }
            return exception;
        }
    }

    /**
     * Whether the write needs publication authority: it touches the policy or a path the policy
     * publishes, changes a published media entry, or repairs an unreadable media index. Decided in
     * two halves, before and after the candidate edits, because the policy and the media index may
     * themselves be among the edits.
     */
    private final class Publication {
        private final RepositoryCandidateChanges changes;
        private final RepositoryPublishingPolicy before;
        private final RepositoryMediaIndex mediaBefore;
        private final boolean changesMedia;
        private final boolean preserveInvalidMedia;
        private boolean needed;

        Publication(Repository repository, DirCache index, RepositoryCandidateChanges changes) throws IOException {
            this.changes = changes;
            Set<String> paths = changes.paths();
            before = policy(repository, index);
            RepositoryMediaIndex media;
            boolean invalidMedia = false;
            try {
                media = mediaIndex(repository, index);
            } catch (IllegalArgumentException exception) {
                // Repair requires publication authority because prior media eligibility is unknown.
                media = RepositoryMediaIndex.empty();
                invalidMedia = true;
            }
            mediaBefore = media;
            needed = paths.stream()
                    .anyMatch(path -> path.equals(RepositoryPublishingPolicy.PATH)
                            || before.state() == RepositoryPublishingPolicy.State.INVALID
                            || before.permitsPath(path));
            changesMedia = paths.contains(RepositoryMediaIndex.PATH);
            preserveInvalidMedia = invalidMedia && !changesMedia;
            if (preserveInvalidMedia && (needed || changes.structural())) {
                throw new IllegalArgumentException("repair the media index before structural or publication changes");
            }
            needed |= invalidMedia && changesMedia;
        }

        /** Whether the write needs publication authority, and the media index it leaves behind. */
        record Decision(boolean publish, RepositoryMediaIndex media) {}

        Decision complete(Repository repository, DirCache index) throws IOException {
            RepositoryPublishingPolicy after = policy(repository, index);
            RepositoryMediaIndex mediaAfter =
                    preserveInvalidMedia ? RepositoryMediaIndex.empty() : mediaIndex(repository, index);
            if (!mediaAfter.files().isEmpty()) {
                mediaAfter.requireNoGitCollisions(IntStream.range(0, index.getEntryCount())
                        .mapToObj(i -> index.getEntry(i).getPathString())
                        .toList());
            }
            Set<String> mediaPaths = new HashSet<>(mediaBefore.files().keySet());
            mediaPaths.addAll(mediaAfter.files().keySet());
            for (String path : mediaPaths) {
                if (!Objects.equals(
                        mediaBefore.files().get(path), mediaAfter.files().get(path))) {
                    needed |= before.permitsPath(path) || after.permitsPath(path);
                }
            }
            needed |= changes.paths().stream().anyMatch(after::permitsPath);
            return new Decision(needed, mediaAfter);
        }

        void validateMedia(WorkspaceId workspace, RepositoryMediaIndex mediaAfter) {
            if (changesMedia) {
                mediaValidator.validate(
                        workspace,
                        mediaAfter.files().values().stream().distinct().toList());
            }
        }
    }

    // Applies the candidate's deletions, replacements and copies to the index; a replacement's
    // revision is the digest of its bytes, a copy keeps its object and mode.
    private static Map<String, Optional<DocumentRevision>> edit(
            DirCache index, ObjectInserter inserter, RepositoryCandidateChanges changes) throws IOException {
        Map<String, Optional<DocumentRevision>> revisions = new LinkedHashMap<>();
        DirCacheEditor editor = index.editor();
        for (String path : changes.paths()) {
            if (changes.deletions().contains(path)) {
                editor.add(new DirCacheEditor.DeletePath(path));
                revisions.put(path, Optional.empty());
                continue;
            }
            byte[] bytes = changes.replacements().get(path);
            var copied = changes.copies().get(path);
            ObjectId blob = bytes != null ? inserter.insert(Constants.OBJ_BLOB, bytes) : copied.objectId();
            DirCacheEntry prior = index.getEntry(path);
            FileMode mode = copied != null
                    ? copied.mode()
                    : prior == null || path.equals(RepositoryMediaIndex.PATH)
                            ? FileMode.REGULAR_FILE
                            : prior.getFileMode();
            editor.add(new DirCacheEditor.PathEdit(path) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(mode);
                    entry.setObjectId(blob);
                    if (bytes != null) {
                        entry.setLength(bytes.length);
                    }
                }
            });
            if (bytes != null) {
                revisions.put(path, Optional.of(DocumentRevision.sha256(bytes)));
            }
        }
        editor.finish();
        inserter.flush();
        return revisions;
    }

    private static void requireRetainedMatch(
            RevCommit parsed,
            RepositoryWriteAttempt retained,
            ObjectId commit,
            ObjectId tree,
            ObjectId base,
            CommitBuilder candidate) {
        PersonIdent author = candidate.getAuthor();
        if (!commit.name().equals(retained.commit())
                || !parsed.getTree().equals(tree)
                || parsed.getParentCount() != (base.equals(ObjectId.zeroId()) ? 0 : 1)
                || (parsed.getParentCount() == 1 && !parsed.getParent(0).equals(base))
                || !parsed.getFullMessage().equals(candidate.getMessage())
                || !parsed.getAuthorIdent().getName().equals(author.getName())
                || !parsed.getAuthorIdent().getEmailAddress().equals(author.getEmailAddress())
                || !parsed.getCommitterIdent().getName().equals(author.getName())
                || !parsed.getCommitterIdent().getEmailAddress().equals(author.getEmailAddress())) {
            throw new IllegalArgumentException("retained attempt does not match the authorized patch");
        }
    }

    private static void checkCandidate(
            DirCache index, Map<String, byte[]> replacements, Repository repository, Set<String> paths)
            throws IOException {
        if (index.getEntryCount() > MAX_TREE_ENTRIES) {
            throw new IllegalArgumentException("repository tree entry limit exceeded");
        }
        Set<String> touched = new HashSet<>();
        paths.forEach(path -> touched.add(DocumentPathRules.collisionKey(path)));
        Map<String, String> seen = new HashMap<>();
        int count = 0;
        long bytes = 0;
        for (int i = 0; i < index.getEntryCount(); i++) {
            String path = index.getEntry(i).getPathString();
            String key = DocumentPathRules.collisionKey(path);
            String previous = seen.putIfAbsent(key, path);
            if (previous != null && touched.contains(key)) {
                throw new IllegalArgumentException("patch creates a path collision");
            }
        }
        for (int i = 0; i < index.getEntryCount(); i++) {
            DirCacheEntry entry = index.getEntry(i);
            String path = entry.getPathString();
            String key = DocumentPathRules.collisionKey(path);
            int slash = key.indexOf('/');
            while (slash >= 0) {
                String ancestor = key.substring(0, slash);
                if (seen.containsKey(ancestor) && (touched.contains(ancestor) || touched.contains(key))) {
                    throw new IllegalArgumentException("patch creates a file/directory collision");
                }
                slash = key.indexOf('/', slash + 1);
            }
            if (RepositoryPathRules.markdown(path)
                    && !RepositoryPathRules.reserved(path)
                    && RepositoryBlobs.isFile(entry.getFileMode())) {
                count++;
                bytes += replacements.containsKey(path)
                        ? replacements.get(path).length
                        : repository
                                .open(entry.getObjectId(), Constants.OBJ_BLOB)
                                .getSize();
            }
        }
        if (count > ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE || bytes > ContentLimits.MAX_WORKSPACE_BYTES) {
            throw new IllegalArgumentException("patch would exceed the workspace text bounds");
        }
    }

    static void requireBoundedTree(Repository repository, ObjectId commit) throws IOException {
        if (commit.equals(ObjectId.zeroId())) {
            return;
        }
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commit).getTree());
            tree.setRecursive(true);
            int count = 0;
            while (tree.next()) {
                if (++count > MAX_TREE_ENTRIES) {
                    throw new IllegalArgumentException("repository tree entry limit exceeded");
                }
            }
        }
    }

    private static Map<String, OriginalEntry> untouchedEntries(DirCache index, Set<String> touched) {
        Map<String, OriginalEntry> originals = new HashMap<>();
        for (int i = 0; i < index.getEntryCount(); i++) {
            DirCacheEntry entry = index.getEntry(i);
            if (!touched.contains(entry.getPathString())) {
                originals.put(
                        entry.getPathString(),
                        new OriginalEntry(entry.getObjectId().copy(), entry.getFileMode()));
            }
        }
        return originals;
    }

    private static void requireUntouched(DirCache index, Map<String, OriginalEntry> originals) {
        // DirCacheEditor can replace a directory or ancestor entry implicitly. Every removed or
        // changed path must belong to the candidate prepared against the checked base.
        originals.forEach((path, original) -> {
            DirCacheEntry candidate = index.getEntry(path);
            if (candidate == null
                    || !candidate.getObjectId().equals(original.objectId())
                    || !candidate.getFileMode().equals(original.mode())) {
                throw new IllegalArgumentException("patch would implicitly replace an unchecked path");
            }
        });
    }

    private record OriginalEntry(ObjectId objectId, FileMode mode) {}

    static RepositoryPublishingPolicy policy(Repository repository, DirCache index) throws IOException {
        DirCacheEntry entry = index.getEntry(RepositoryPublishingPolicy.PATH);
        if (entry == null) {
            return RepositoryPublishingPolicy.missing();
        }
        if (!RepositoryBlobs.isFile(entry.getFileMode())) {
            return RepositoryPublishingPolicy.parse(null);
        }
        ObjectLoader blob = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
        if (blob.getSize() > RepositoryPublishingPolicy.MAX_BYTES) {
            return RepositoryPublishingPolicy.parse(null);
        }
        return RepositoryPublishingPolicy.parse(blob.getBytes(RepositoryPublishingPolicy.MAX_BYTES));
    }

    static RepositoryMediaIndex mediaIndex(Repository repository, DirCache index) throws IOException {
        DirCacheEntry entry = index.getEntry(RepositoryMediaIndex.PATH);
        if (entry == null) {
            return RepositoryMediaIndex.empty();
        }
        if (!RepositoryBlobs.isPlainFile(entry.getFileMode())) {
            throw new IllegalArgumentException("repository media index must be a regular file");
        }
        ObjectLoader blob = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
        if (blob.getSize() > RepositoryMediaIndex.MAX_BYTES) {
            throw new IllegalArgumentException("repository media index exceeds its byte limit");
        }
        return RepositoryMediaIndex.parse(blob.getBytes(RepositoryMediaIndex.MAX_BYTES));
    }
}
