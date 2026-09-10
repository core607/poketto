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
import io.github.core607.poketto.content.RepositoryMovePlan;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
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
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JGitRepositoryPatchService implements RepositoryPatchService, RepositoryMoveService {
    private static final Logger log = LoggerFactory.getLogger(JGitRepositoryPatchService.class);
    private static final int MAX_TREE_ENTRIES = 100_000;
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "tif", "tiff");
    private final RepositoryAuthority authority;
    private final AuthService auth;
    private final Clock clock;
    private final BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installAcknowledged;
    private final BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> closePublication;
    private final RepositoryMediaValidator mediaValidator;

    JGitRepositoryPatchService(
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
        this.mediaValidator = java.util.Objects.requireNonNull(mediaValidator);
    }

    @Override
    public RepositoryPatchResult apply(AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch) {
        return apply(principal, workspace, patch, Optional.empty());
    }

    @Override
    public RepositoryPatchResult recover(
            AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch, RepositoryWriteAttempt attempt) {
        auth.authorize(principal, workspace, Capability.READ_PRIVATE);
        return apply(principal, workspace, patch, Optional.of(attempt));
    }

    private RepositoryPatchResult apply(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPatch patch,
            Optional<RepositoryWriteAttempt> recovery) {
        Map<String, byte[]> replacements = validate(patch);
        return write(
                principal,
                workspace,
                patch.baseCommit(),
                Set.of(Capability.WRITE_PRIVATE),
                recovery,
                (repository, index) -> {
                    checkBase(repository, index, patch);
                    Set<String> deletions = new HashSet<>();
                    patch.changes().stream()
                            .filter(change -> change.content().isEmpty())
                            .forEach(change -> deletions.add(change.path()));
                    boolean structural = patch.changes().stream()
                            .anyMatch(change -> change.expectedAbsence()
                                    || change.content().isEmpty()
                                    || RepositoryPathRules.reserved(change.path()));
                    return new RepositoryCandidateChanges(replacements, Map.of(), deletions, structural);
                });
    }

    @Override
    public RepositoryMovePlan plan(AuthPrincipal principal, WorkspaceId workspace, RepositoryMoveRequest request) {
        return auth.withAuthorization(
                principal,
                workspace,
                Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE),
                () -> authority.readObjects(workspace, snapshot -> {
                    if (!snapshot.commitId().equals(Optional.of(request.baseCommit())))
                        throw new RepositoryConflictException("repository base changed before preparing move");
                    try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspace);
                            RevWalk walk = new RevWalk(repository);
                            var reader = repository.newObjectReader()) {
                        ObjectId base = ObjectId.fromString(request.baseCommit());
                        requireBoundedTree(repository, base);
                        DirCache index =
                                DirCache.read(reader, walk.parseCommit(base).getTree());
                        var media = mediaIndex(repository, index);
                        var changes = RepositoryMovePlanner.prepare(
                                repository, index, request, policy(repository, index), media);
                        var namespace = new HashSet<>(media.files().keySet());
                        for (int i = 0; i < index.getEntryCount(); i++)
                            namespace.add(index.getEntry(i).getPathString());
                        var relocations = RepositoryMovePlanner.relocate(namespace, request);
                        var affected = new HashSet<>(changes.paths());
                        affected.addAll(relocations.keySet());
                        affected.addAll(relocations.values());
                        if (affected.size() > RepositoryMovePlan.MAX_CHANGED_PATHS)
                            throw new IllegalArgumentException("move exceeds session path capacity");
                        var originals = new LinkedHashMap<String, RepositoryMovePlan.Original>();
                        long originalBytes = 0;
                        for (String path : changes.paths()) {
                            var entry = index.getEntry(path);
                            if (entry != null) {
                                originalBytes += repository
                                        .open(entry.getObjectId(), Constants.OBJ_BLOB)
                                        .getSize();
                                if (originalBytes > ContentLimits.MAX_WORKSPACE_BYTES)
                                    throw new IllegalArgumentException(
                                            "move fingerprinting exceeds workspace byte capacity");
                                originals.put(path, moveOriginal(repository, entry.getObjectId()));
                            }
                        }
                        for (String path : relocations.keySet()) {
                            var original = media.files().get(path);
                            if (original != null)
                                originals.put(
                                        path,
                                        new RepositoryMovePlan.Original(original.revision(), original.size(), true));
                        }
                        return new RepositoryMovePlan(
                                workspace, request, originals, relocations, changes.replacements());
                    } catch (IOException error) {
                        throw new ContentRepositoryException("move preconditions could not be prepared", error);
                    }
                }));
    }

    private static RepositoryMovePlan.Original moveOriginal(Repository repository, ObjectId id) throws IOException {
        var blob = repository.open(id, Constants.OBJ_BLOB);
        if (blob.getSize() > RepositoryMovePlan.MAX_ORIGINAL_BYTES)
            throw new IllegalArgumentException("move original exceeds session file capacity");
        try (var stream = blob.openStream()) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long bytes = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                bytes += count;
                if (bytes > blob.getSize()) throw new IOException("move original size changed");
                digest.update(buffer, 0, count);
            }
            if (bytes != blob.getSize()) throw new IOException("move original is incomplete");
            return new RepositoryMovePlan.Original(java.util.HexFormat.of().formatHex(digest.digest()), bytes, false);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Override
    public RepositoryPatchResult move(AuthPrincipal principal, WorkspaceId workspace, RepositoryMoveRequest request) {
        return move(principal, workspace, request, Optional.empty());
    }

    @Override
    public RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteAttempt attempt) {
        return move(principal, workspace, request, Optional.of(attempt));
    }

    private RepositoryPatchResult move(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            Optional<RepositoryWriteAttempt> recovery) {
        return write(
                principal,
                workspace,
                Optional.of(request.baseCommit()),
                Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE),
                recovery,
                (repository, index) -> RepositoryMovePlanner.prepare(
                        repository, index, request, policy(repository, index), mediaIndex(repository, index)));
    }

    private RepositoryPatchResult write(
            AuthPrincipal principal,
            WorkspaceId workspace,
            Optional<String> baseCommit,
            Set<Capability> capabilities,
            Optional<RepositoryWriteAttempt> recovery,
            Preparer preparer) {
        boolean[] acknowledged = {false};
        RepositoryWriteAttempt[] attempt = {null};
        try {
            return auth.withAuthorization(
                    principal,
                    workspace,
                    capabilities,
                    () -> authority.writeObjects(workspace, (snapshot, advancer) -> {
                        if (recovery.isEmpty() && !snapshot.commitId().equals(baseCommit)) {
                            throw new RepositoryConflictException(
                                    "repository base commit changed; read current files before retrying");
                        }
                        try (Repository repository =
                                        JGitContentRepositoryStore.openCache(snapshot.worktree(), workspace);
                                RevWalk walk = new RevWalk(repository);
                                var reader = repository.newObjectReader();
                                ObjectInserter inserter = repository.newObjectInserter()) {
                            ObjectId base = baseCommit.map(ObjectId::fromString).orElse(ObjectId.zeroId());
                            requireBoundedTree(repository, base);
                            DirCache index = base.equals(ObjectId.zeroId())
                                    ? DirCache.newInCore()
                                    : DirCache.read(
                                            reader, walk.parseCommit(base).getTree());
                            RepositoryCandidateChanges changes = preparer.prepare(repository, index);
                            Map<String, byte[]> replacements = changes.replacements();
                            Set<String> paths = changes.paths();
                            Map<String, OriginalEntry> untouched = untouchedEntries(index, paths);
                            RepositoryPublishingPolicy before = policy(repository, index);
                            RepositoryMediaIndex mediaBefore;
                            boolean invalidMediaBefore = false;
                            try {
                                mediaBefore = mediaIndex(repository, index);
                            } catch (IllegalArgumentException exception) {
                                // Repair requires publication authority because prior media eligibility is unknown.
                                mediaBefore = RepositoryMediaIndex.empty();
                                invalidMediaBefore = true;
                            }
                            boolean needsPublish = paths.stream()
                                    .anyMatch(path -> path.equals(RepositoryPublishingPolicy.PATH)
                                            || before.state() == RepositoryPublishingPolicy.State.INVALID
                                            || before.permitsPath(path));
                            boolean changesMedia = paths.contains(RepositoryMediaIndex.PATH);
                            boolean preserveInvalidMedia = invalidMediaBefore && !changesMedia;
                            if (preserveInvalidMedia && (needsPublish || changes.structural()))
                                throw new IllegalArgumentException(
                                        "repair the media index before structural or publication changes");
                            needsPublish |= invalidMediaBefore && changesMedia;
                            Map<String, Optional<DocumentRevision>> revisions = new LinkedHashMap<>();
                            DirCacheEditor editor = index.editor();
                            for (String path : paths) {
                                if (changes.deletions().contains(path)) {
                                    editor.add(new DirCacheEditor.DeletePath(path));
                                    revisions.put(path, Optional.empty());
                                } else {
                                    byte[] bytes = replacements.get(path);
                                    var copied = changes.copies().get(path);
                                    ObjectId blob = bytes != null
                                            ? inserter.insert(Constants.OBJ_BLOB, bytes)
                                            : copied.objectId();
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
                                            if (bytes != null) entry.setLength(bytes.length);
                                        }
                                    });
                                    if (bytes != null) revisions.put(path, Optional.of(DocumentRevision.sha256(bytes)));
                                }
                            }
                            editor.finish();
                            inserter.flush();
                            requireUntouched(index, untouched);
                            checkCandidate(index, replacements, repository, paths);
                            RepositoryPublishingPolicy after = policy(repository, index);
                            RepositoryMediaIndex mediaAfter =
                                    preserveInvalidMedia ? RepositoryMediaIndex.empty() : mediaIndex(repository, index);
                            if (!mediaAfter.files().isEmpty())
                                mediaAfter.requireNoGitCollisions(
                                        java.util.stream.IntStream.range(0, index.getEntryCount())
                                                .mapToObj(i -> index.getEntry(i).getPathString())
                                                .toList());
                            Set<String> mediaPaths =
                                    new HashSet<>(mediaBefore.files().keySet());
                            mediaPaths.addAll(mediaAfter.files().keySet());
                            for (String path : mediaPaths) {
                                if (!java.util.Objects.equals(
                                        mediaBefore.files().get(path),
                                        mediaAfter.files().get(path)))
                                    needsPublish |= before.permitsPath(path) || after.permitsPath(path);
                            }
                            needsPublish |= paths.stream().anyMatch(after::permitsPath);
                            if (needsPublish) auth.authorize(principal, workspace, Capability.PUBLISH);
                            if (changesMedia)
                                mediaValidator.validate(
                                        workspace,
                                        mediaAfter.files().values().stream()
                                                .distinct()
                                                .toList());
                            ObjectId tree = index.writeTree(inserter);
                            if (!base.equals(ObjectId.zeroId())
                                    && tree.equals(
                                            walk.parseCommit(base).getTree().getId())) {
                                if (recovery.isPresent())
                                    throw new IllegalArgumentException(
                                            "retained attempt cannot describe an unchanged tree");
                                return new RepositoryPatchResult(base.name(), false, false, revisions);
                            }
                            CommitBuilder candidate = new CommitBuilder();
                            candidate.setTreeId(tree);
                            if (!base.equals(ObjectId.zeroId())) candidate.setParentId(base);
                            PersonIdent author =
                                    new PersonIdent("Poketto", "poketto@invalid", clock.instant(), ZoneOffset.UTC);
                            candidate.setAuthor(author);
                            candidate.setCommitter(author);
                            WritePrincipal attribution = new WritePrincipal(
                                    principal.kind() == AuthPrincipal.Kind.ACCOUNT
                                            ? PrincipalType.ACCOUNT
                                            : PrincipalType.API_KEY,
                                    principal.subjectId().toString());
                            candidate.setMessage("Apply repository changes\n\nPoketto-Principal: "
                                    + attribution.trailerValue() + "\n");
                            byte[] commitBytes = recovery.isPresent()
                                    ? recovery.orElseThrow().object()
                                    : candidate.build();
                            ObjectId commit = inserter.insert(Constants.OBJ_COMMIT, commitBytes);
                            inserter.flush();
                            if (recovery.isPresent()) {
                                var retained = recovery.orElseThrow();
                                var parsed = walk.parseCommit(commit);
                                if (!commit.name().equals(retained.commit())
                                        || !parsed.getTree().equals(tree)
                                        || parsed.getParentCount() != (base.equals(ObjectId.zeroId()) ? 0 : 1)
                                        || (parsed.getParentCount() == 1
                                                && !parsed.getParent(0).equals(base))
                                        || !parsed.getFullMessage().equals(candidate.getMessage())
                                        || !parsed.getAuthorIdent().getName().equals(author.getName())
                                        || !parsed.getAuthorIdent()
                                                .getEmailAddress()
                                                .equals(author.getEmailAddress())
                                        || !parsed.getCommitterIdent().getName().equals(author.getName())
                                        || !parsed.getCommitterIdent()
                                                .getEmailAddress()
                                                .equals(author.getEmailAddress()))
                                    throw new IllegalArgumentException(
                                            "retained attempt does not match the authorized patch");
                                attempt[0] = retained;
                                ObjectId current = snapshot.commitId()
                                        .map(ObjectId::fromString)
                                        .orElse(ObjectId.zeroId());
                                if (!current.equals(ObjectId.zeroId())
                                        && walk.isMergedInto(parsed, walk.parseCommit(current))) {
                                    // Reconciliation reads current remote history and never pushes a duplicate.
                                    // A later remote edit must not be hidden by installing the older candidate.
                                    acknowledged[0] = true;
                                    boolean installed = false;
                                    try {
                                        installAcknowledged.accept(workspace, snapshot);
                                        installed = true;
                                    } catch (RuntimeException unavailable) {
                                        log.warn(
                                                "workspace {} recovered commit {} but current snapshot installation failed",
                                                workspace,
                                                commit.name());
                                    }
                                    return new RepositoryPatchResult(commit.name(), true, installed, revisions);
                                }
                                if (!snapshot.commitId().equals(baseCommit))
                                    throw new RepositoryConflictException(
                                            "remote main diverged from the retained write attempt");
                            }
                            attempt[0] = new RepositoryWriteAttempt(commit.name(), commitBytes);
                            // Close the prior authorization before a remote outcome can become uncertain.
                            // Failure to persist this marker must prevent the push itself.
                            if (needsPublish) closePublication.accept(workspace, snapshot);
                            advancer.advance(commit.name());
                            acknowledged[0] = true;
                            boolean snapshotUpdated = false;
                            try {
                                installAcknowledged.accept(
                                        workspace,
                                        new RepositoryAuthority.Snapshot(
                                                snapshot.worktree(), Optional.of(commit.name())));
                                snapshotUpdated = true;
                            } catch (RuntimeException exception) {
                                // The snapshot service closes public reads on installation failure.
                                // Remote acknowledgement cannot be undone by a derived-view failure.
                                log.warn(
                                        "workspace {} acknowledged commit {} but public snapshot installation failed",
                                        workspace,
                                        commit.name());
                            }
                            return new RepositoryPatchResult(commit.name(), true, snapshotUpdated, revisions);
                        } catch (IOException exception) {
                            throw new ContentRepositoryException("repository changes could not be prepared", exception);
                        }
                    }));
        } catch (RuntimeException exception) {
            if (exception instanceof RepositoryWriteAmbiguousException unknown && attempt[0] != null)
                throw new RepositoryWriteAmbiguousException(unknown.getMessage(), attempt[0]);
            if (acknowledged[0]) {
                throw new RepositoryWriteAmbiguousException(
                        "remote acknowledged the patch but local completion failed; read remote main before retrying",
                        attempt[0]);
            }
            throw exception;
        }
    }

    @FunctionalInterface
    private interface Preparer {
        RepositoryCandidateChanges prepare(Repository repository, DirCache index) throws IOException;
    }

    private static Map<String, byte[]> validate(RepositoryPatch patch) {
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (RepositoryTextChange change : patch.changes()) {
            String path = RepositoryPathRules.validate(change.path());
            if (!paths.add(DocumentPathRules.collisionKey(path)))
                throw new IllegalArgumentException("patch paths collide");
            if (RepositoryPathRules.reserved(path)
                    && !path.equals(RepositoryPublishingPolicy.PATH)
                    && !path.equals(RepositoryMediaIndex.PATH)) {
                throw new IllegalArgumentException("repository metadata cannot be changed through a text patch");
            }
            String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (IMAGE_EXTENSIONS.contains(extension))
                throw new IllegalArgumentException("repository images are read-only");
            if (change.content().isEmpty()) continue;
            byte[] bytes = encode(change.content().orElseThrow());
            total += bytes.length;
            if (total > RepositoryPatch.MAX_BYTES) throw new IllegalArgumentException("patch exceeds its byte limit");
            if (path.equals(RepositoryPublishingPolicy.PATH)) {
                if (RepositoryPublishingPolicy.parse(bytes).state() == RepositoryPublishingPolicy.State.INVALID) {
                    throw new IllegalArgumentException("replacement publication policy is invalid");
                }
            } else if (path.equals(RepositoryMediaIndex.PATH)) {
                RepositoryMediaIndex.parse(bytes);
            } else if (RepositoryPathRules.markdown(path)) {
                new RepositoryMarkdownParser().parse(path, change.content().orElseThrow());
            }
            replacements.put(path, bytes);
        }
        return replacements;
    }

    private static byte[] encode(String source) {
        if (source.length() > ContentLimits.MAX_DOCUMENT_BYTES || source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("text exceeds its size limit or contains NUL");
        }
        try {
            var encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(source));
            if (encoded.remaining() > ContentLimits.MAX_DOCUMENT_BYTES)
                throw new IllegalArgumentException("text exceeds its byte limit");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("replacement is not valid UTF-8 text");
        }
    }

    private static void checkBase(Repository repository, DirCache index, RepositoryPatch patch) throws IOException {
        for (RepositoryTextChange change : patch.changes()) {
            DirCacheEntry entry = index.getEntry(change.path());
            if (change.expectedAbsence()) {
                if (entry != null || index.getEntriesWithin(change.path() + "/").length != 0) {
                    throw new RepositoryConflictException("expected-absent path already exists");
                }
            } else {
                if (entry == null) throw new RepositoryConflictException("expected file no longer exists");
                FileMode mode = entry.getFileMode();
                if (!mode.equals(FileMode.REGULAR_FILE) && !mode.equals(FileMode.EXECUTABLE_FILE)) {
                    throw new IllegalArgumentException("patch target must be a regular text file");
                }
                ObjectLoader blob = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
                if (blob.getSize() > ContentLimits.MAX_DOCUMENT_BYTES)
                    throw new IllegalArgumentException("patch target exceeds its byte limit");
                byte[] bytes = blob.getBytes(ContentLimits.MAX_DOCUMENT_BYTES);
                RepositoryMarkdownParser.decode(bytes);
                if (!DocumentRevision.sha256(bytes)
                        .equals(change.expectedRevision().orElseThrow())) {
                    throw new RepositoryConflictException(
                            "file revision changed; read the current file before retrying");
                }
            }
        }
    }

    private static void checkCandidate(
            DirCache index, Map<String, byte[]> replacements, Repository repository, Set<String> paths)
            throws IOException {
        if (index.getEntryCount() > MAX_TREE_ENTRIES)
            throw new IllegalArgumentException("repository tree entry limit exceeded");
        Set<String> touched = new HashSet<>();
        paths.forEach(path -> touched.add(DocumentPathRules.collisionKey(path)));
        Map<String, String> seen = new HashMap<>();
        int count = 0;
        long bytes = 0;
        for (int i = 0; i < index.getEntryCount(); i++) {
            String path = index.getEntry(i).getPathString();
            String key = DocumentPathRules.collisionKey(path);
            String previous = seen.putIfAbsent(key, path);
            if (previous != null && touched.contains(key))
                throw new IllegalArgumentException("patch creates a path collision");
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
                    && (entry.getFileMode().equals(FileMode.REGULAR_FILE)
                            || entry.getFileMode().equals(FileMode.EXECUTABLE_FILE))) {
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

    private static void requireBoundedTree(Repository repository, ObjectId commit) throws IOException {
        if (commit.equals(ObjectId.zeroId())) return;
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commit).getTree());
            tree.setRecursive(true);
            int count = 0;
            while (tree.next()) {
                if (++count > MAX_TREE_ENTRIES)
                    throw new IllegalArgumentException("repository tree entry limit exceeded");
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

    private static RepositoryPublishingPolicy policy(Repository repository, DirCache index) throws IOException {
        DirCacheEntry entry = index.getEntry(RepositoryPublishingPolicy.PATH);
        if (entry == null) return RepositoryPublishingPolicy.missing();
        if (!entry.getFileMode().equals(FileMode.REGULAR_FILE)
                && !entry.getFileMode().equals(FileMode.EXECUTABLE_FILE)) {
            return RepositoryPublishingPolicy.parse(null);
        }
        ObjectLoader blob = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
        if (blob.getSize() > RepositoryPublishingPolicy.MAX_BYTES) return RepositoryPublishingPolicy.parse(null);
        return RepositoryPublishingPolicy.parse(blob.getBytes(RepositoryPublishingPolicy.MAX_BYTES));
    }

    private static RepositoryMediaIndex mediaIndex(Repository repository, DirCache index) throws IOException {
        DirCacheEntry entry = index.getEntry(RepositoryMediaIndex.PATH);
        if (entry == null) return RepositoryMediaIndex.empty();
        if (!FileMode.REGULAR_FILE.equals(entry.getFileMode()))
            throw new IllegalArgumentException("repository media index must be a regular file");
        ObjectLoader blob = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
        if (blob.getSize() > RepositoryMediaIndex.MAX_BYTES)
            throw new IllegalArgumentException("repository media index exceeds its byte limit");
        return RepositoryMediaIndex.parse(blob.getBytes(RepositoryMediaIndex.MAX_BYTES));
    }
}
