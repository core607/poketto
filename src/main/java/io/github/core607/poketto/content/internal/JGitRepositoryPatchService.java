package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
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

final class JGitRepositoryPatchService implements RepositoryPatchService {
    private static final Logger log = LoggerFactory.getLogger(JGitRepositoryPatchService.class);
    private static final int MAX_TREE_ENTRIES = 100_000;
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "tif", "tiff");
    private final RepositoryAuthority authority;
    private final AuthService auth;
    private final Clock clock;
    private final BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installAcknowledged;

    JGitRepositoryPatchService(
            RepositoryAuthority authority,
            AuthService auth,
            Clock clock,
            BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installAcknowledged) {
        this.authority = authority;
        this.auth = auth;
        this.clock = clock;
        this.installAcknowledged = installAcknowledged;
    }

    @Override
    public RepositoryPatchResult apply(AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch) {
        Map<String, byte[]> replacements = validate(patch);
        boolean[] acknowledged = {false};
        try {
            return auth.withAuthorization(
                    principal,
                    workspace,
                    Set.of(Capability.WRITE_PRIVATE),
                    () -> authority.writeObjects(workspace, (snapshot, advancer) -> {
                        if (!snapshot.commitId().equals(patch.baseCommit())) {
                            throw new RepositoryConflictException(
                                    "repository base commit changed; read current files before retrying");
                        }
                        try (Repository repository =
                                        JGitContentRepositoryStore.openCache(snapshot.worktree(), workspace);
                                RevWalk walk = new RevWalk(repository);
                                var reader = repository.newObjectReader();
                                ObjectInserter inserter = repository.newObjectInserter()) {
                            ObjectId base = snapshot.commitId()
                                    .map(ObjectId::fromString)
                                    .orElse(ObjectId.zeroId());
                            requireBoundedTree(repository, base);
                            DirCache index = base.equals(ObjectId.zeroId())
                                    ? DirCache.newInCore()
                                    : DirCache.read(
                                            reader, walk.parseCommit(base).getTree());
                            checkBase(repository, index, patch);
                            Map<String, OriginalEntry> untouched = untouchedEntries(index, patch);
                            RepositoryPublishingPolicy before = policy(repository, index);
                            boolean needsPublish = patch.changes().stream()
                                    .anyMatch(change -> change.path().equals(RepositoryPublishingPolicy.PATH)
                                            || before.state() == RepositoryPublishingPolicy.State.INVALID
                                            || before.permitsPath(change.path()));
                            Map<String, Optional<DocumentRevision>> revisions = new LinkedHashMap<>();
                            DirCacheEditor editor = index.editor();
                            for (RepositoryTextChange change : patch.changes()) {
                                if (change.content().isEmpty()) {
                                    editor.add(new DirCacheEditor.DeletePath(change.path()));
                                    revisions.put(change.path(), Optional.empty());
                                } else {
                                    byte[] bytes = replacements.get(change.path());
                                    ObjectId blob = inserter.insert(Constants.OBJ_BLOB, bytes);
                                    DirCacheEntry prior = index.getEntry(change.path());
                                    FileMode mode = prior == null ? FileMode.REGULAR_FILE : prior.getFileMode();
                                    editor.add(new DirCacheEditor.PathEdit(change.path()) {
                                        @Override
                                        public void apply(DirCacheEntry entry) {
                                            entry.setFileMode(mode);
                                            entry.setObjectId(blob);
                                            entry.setLength(bytes.length);
                                        }
                                    });
                                    revisions.put(change.path(), Optional.of(DocumentRevision.sha256(bytes)));
                                }
                            }
                            editor.finish();
                            inserter.flush();
                            requireUntouched(index, untouched);
                            checkCandidate(index, replacements, repository, patch);
                            RepositoryPublishingPolicy after = policy(repository, index);
                            needsPublish |=
                                    patch.changes().stream().anyMatch(change -> after.permitsPath(change.path()));
                            if (needsPublish) auth.authorize(principal, workspace, Capability.PUBLISH);
                            ObjectId tree = index.writeTree(inserter);
                            if (!base.equals(ObjectId.zeroId())
                                    && tree.equals(
                                            walk.parseCommit(base).getTree().getId())) {
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
                            candidate.setMessage("Apply repository text patch\n\nPoketto-Principal: "
                                    + attribution.trailerValue() + "\n");
                            ObjectId commit = inserter.insert(candidate);
                            inserter.flush();
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
                            throw new ContentRepositoryException(
                                    "repository text patch could not be prepared", exception);
                        }
                    }));
        } catch (RuntimeException exception) {
            if (acknowledged[0]) {
                throw new RepositoryWriteAmbiguousException(
                        "remote acknowledged the patch but local completion failed; read remote main before retrying");
            }
            throw exception;
        }
    }

    private static Map<String, byte[]> validate(RepositoryPatch patch) {
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (RepositoryTextChange change : patch.changes()) {
            String path = RepositoryPathRules.validate(change.path());
            if (!paths.add(DocumentPathRules.collisionKey(path)))
                throw new IllegalArgumentException("patch paths collide");
            if (RepositoryPathRules.reserved(path) && !path.equals(RepositoryPublishingPolicy.PATH)) {
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
            DirCache index, Map<String, byte[]> replacements, Repository repository, RepositoryPatch patch)
            throws IOException {
        if (index.getEntryCount() > MAX_TREE_ENTRIES)
            throw new IllegalArgumentException("repository tree entry limit exceeded");
        Set<String> touched = new HashSet<>();
        patch.changes().forEach(change -> touched.add(DocumentPathRules.collisionKey(change.path())));
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

    private static Map<String, OriginalEntry> untouchedEntries(DirCache index, RepositoryPatch patch) {
        Set<String> touched = new HashSet<>();
        patch.changes().forEach(change -> touched.add(change.path()));
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
        // changed path must instead have its own caller-supplied revision precondition.
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
}
