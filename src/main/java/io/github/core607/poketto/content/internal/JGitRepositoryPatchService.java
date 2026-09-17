package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
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
import io.github.core607.poketto.content.RepositoryWriteAttempt;
import io.github.core607.poketto.content.RepositoryWriteCheckpoint;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

final class JGitRepositoryPatchService implements RepositoryPatchService, RepositoryMoveService {
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "tif", "tiff");
    private final RepositoryAuthority authority;
    private final AuthService auth;
    private final JGitRepositoryWrites writes;

    JGitRepositoryPatchService(
            RepositoryAuthority authority,
            AuthService auth,
            Clock clock,
            BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installAcknowledged,
            BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> closePublication,
            RepositoryMediaValidator mediaValidator) {
        this.authority = authority;
        this.auth = auth;
        this.writes =
                new JGitRepositoryWrites(authority, auth, clock, installAcknowledged, closePublication, mediaValidator);
    }

    @Override
    public RepositoryPatchResult apply(AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch) {
        return apply(principal, workspace, patch, RepositoryWriteCheckpoint.UNTRACKED);
    }

    @Override
    public RepositoryPatchResult apply(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPatch patch,
            RepositoryWriteCheckpoint checkpoint) {
        return apply(principal, workspace, patch, Optional.empty(), checkpoint);
    }

    @Override
    public RepositoryPatchResult recover(
            AuthPrincipal principal, WorkspaceId workspace, RepositoryPatch patch, RepositoryWriteAttempt attempt) {
        return recover(principal, workspace, patch, attempt, RepositoryWriteCheckpoint.UNTRACKED);
    }

    @Override
    public RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPatch patch,
            RepositoryWriteAttempt attempt,
            RepositoryWriteCheckpoint checkpoint) {
        return apply(principal, workspace, patch, Optional.of(attempt), checkpoint);
    }

    private RepositoryPatchResult apply(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPatch patch,
            Optional<RepositoryWriteAttempt> recovery,
            RepositoryWriteCheckpoint checkpoint) {
        Map<String, byte[]> replacements = validate(patch);
        return writes.write(
                principal, workspace, patch.baseCommit(), Set.of(), recovery, checkpoint, (repository, index) -> {
                    var currentPolicy = JGitRepositoryWrites.policy(repository, index);
                    Set<Capability> required = patch.changes().stream()
                            .map(change -> currentPolicy.permitsPath(change.path())
                                    ? Capability.PUBLISH
                                    : Capability.WRITE_PRIVATE)
                            .collect(Collectors.toSet());
                    auth.withAuthorization(principal, workspace, required, () -> null);
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
                Set.of(),
                () -> authority.readObjects(workspace, snapshot -> {
                    if (!snapshot.commitId().equals(Optional.of(request.baseCommit()))) {
                        throw new RepositoryConflictException("repository base changed before preparing move");
                    }
                    try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspace);
                            RevWalk walk = new RevWalk(repository);
                            var reader = repository.newObjectReader()) {
                        ObjectId base = ObjectId.fromString(request.baseCommit());
                        JGitRepositoryWrites.requireBoundedTree(repository, base);
                        DirCache index =
                                DirCache.read(reader, walk.parseCommit(base).getTree());
                        var media = JGitRepositoryWrites.mediaIndex(repository, index);
                        var currentPolicy = JGitRepositoryWrites.policy(repository, index);
                        var changes = RepositoryMovePlanner.prepare(repository, index, request, currentPolicy, media);
                        authorizeMoveChanges(principal, workspace, currentPolicy, media, changes, true);
                        Set<String> namespace = moveNamespace(index, media);
                        var relocations = RepositoryMovePlanner.relocate(namespace, request);
                        var affected = new HashSet<>(changes.paths());
                        affected.addAll(relocations.keySet());
                        affected.addAll(relocations.values());
                        if (affected.size() > RepositoryMovePlan.MAX_CHANGED_PATHS) {
                            throw new IllegalArgumentException("move exceeds session path capacity");
                        }
                        var originals = new LinkedHashMap<String, RepositoryMovePlan.Original>();
                        long originalBytes = 0;
                        for (String path : changes.paths()) {
                            var entry = index.getEntry(path);
                            if (entry != null) {
                                originalBytes += repository
                                        .open(entry.getObjectId(), Constants.OBJ_BLOB)
                                        .getSize();
                                if (originalBytes > ContentLimits.MAX_WORKSPACE_BYTES) {
                                    throw new IllegalArgumentException(
                                            "move fingerprinting exceeds workspace byte capacity");
                                }
                                originals.put(path, moveOriginal(repository, entry.getObjectId()));
                            }
                        }
                        for (String path : relocations.keySet()) {
                            var original = media.files().get(path);
                            if (original != null) {
                                originals.put(
                                        path,
                                        new RepositoryMovePlan.Original(original.revision(), original.size(), true));
                            }
                        }
                        return new RepositoryMovePlan(
                                workspace, request, originals, relocations, changes.replacements());
                    } catch (IOException error) {
                        throw new ContentRepositoryException("move preconditions could not be prepared", error);
                    }
                }));
    }

    private static Set<String> moveNamespace(DirCache index, RepositoryMediaIndex media) {
        var namespace = new HashSet<>(media.files().keySet());
        for (int i = 0; i < index.getEntryCount(); i++) {
            namespace.add(index.getEntry(i).getPathString());
        }
        return namespace;
    }

    private static RepositoryMovePlan.Original moveOriginal(Repository repository, ObjectId id) throws IOException {
        var blob = repository.open(id, Constants.OBJ_BLOB);
        if (blob.getSize() > RepositoryMovePlan.MAX_ORIGINAL_BYTES) {
            throw new IllegalArgumentException("move original exceeds session file capacity");
        }
        try (var stream = blob.openStream()) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long bytes = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                bytes += count;
                if (bytes > blob.getSize()) {
                    throw new IOException("move original size changed");
                }
                digest.update(buffer, 0, count);
            }
            if (bytes != blob.getSize()) {
                throw new IOException("move original is incomplete");
            }
            return new RepositoryMovePlan.Original(HexFormat.of().formatHex(digest.digest()), bytes, false);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Override
    public RepositoryPatchResult move(AuthPrincipal principal, WorkspaceId workspace, RepositoryMoveRequest request) {
        return move(principal, workspace, request, RepositoryWriteCheckpoint.UNTRACKED);
    }

    @Override
    public RepositoryPatchResult move(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteCheckpoint checkpoint) {
        return move(principal, workspace, request, Optional.empty(), checkpoint);
    }

    @Override
    public RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteAttempt attempt) {
        return recover(principal, workspace, request, attempt, RepositoryWriteCheckpoint.UNTRACKED);
    }

    @Override
    public RepositoryPatchResult recover(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            RepositoryWriteAttempt attempt,
            RepositoryWriteCheckpoint checkpoint) {
        return move(principal, workspace, request, Optional.of(attempt), checkpoint);
    }

    private RepositoryPatchResult move(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryMoveRequest request,
            Optional<RepositoryWriteAttempt> recovery,
            RepositoryWriteCheckpoint checkpoint) {
        return writes.write(
                principal,
                workspace,
                Optional.of(request.baseCommit()),
                Set.of(),
                recovery,
                checkpoint,
                (repository, index) -> {
                    var currentPolicy = JGitRepositoryWrites.policy(repository, index);
                    var media = JGitRepositoryWrites.mediaIndex(repository, index);
                    var changes = RepositoryMovePlanner.prepare(repository, index, request, currentPolicy, media);
                    authorizeMoveChanges(principal, workspace, currentPolicy, media, changes, false);
                    return changes;
                });
    }

    private void authorizeMoveChanges(
            AuthPrincipal principal,
            WorkspaceId workspace,
            RepositoryPublishingPolicy policy,
            RepositoryMediaIndex before,
            RepositoryCandidateChanges changes,
            boolean disclosePlan) {
        Set<String> paths = new HashSet<>(changes.paths());
        paths.remove(RepositoryMediaIndex.PATH);
        byte[] replacement = changes.replacements().get(RepositoryMediaIndex.PATH);
        Set<Capability> required = new HashSet<>();
        if (replacement != null) {
            var after = RepositoryMediaIndex.parse(replacement);
            Set<String> mediaPaths = new HashSet<>(before.files().keySet());
            mediaPaths.addAll(after.files().keySet());
            for (String path : mediaPaths) {
                if (!Objects.equals(before.files().get(path), after.files().get(path))) {
                    paths.add(path);
                }
                // An emitted plan contains the complete replacement index, including untouched private entries.
                if (disclosePlan && !policy.permitsPath(path)) {
                    required.add(Capability.READ_PRIVATE);
                }
            }
        }
        for (String path : paths) {
            if (policy.permitsPath(path)) {
                required.add(Capability.PUBLISH);
            } else {
                required.add(Capability.READ_PRIVATE);
                required.add(Capability.WRITE_PRIVATE);
            }
        }
        auth.withAuthorization(principal, workspace, required, () -> null);
    }

    private static Map<String, byte[]> validate(RepositoryPatch patch) {
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (RepositoryTextChange change : patch.changes()) {
            String path = RepositoryPathRules.validate(change.path());
            if (!paths.add(DocumentPathRules.collisionKey(path))) {
                throw new IllegalArgumentException("patch paths collide");
            }
            if (RepositoryPathRules.reserved(path)
                    && !path.equals(RepositoryPublishingPolicy.PATH)
                    && !path.equals(RepositoryMediaIndex.PATH)) {
                throw new IllegalArgumentException("repository metadata cannot be changed through a text patch");
            }
            String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (IMAGE_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("repository images are read-only");
            }
            if (change.content().isEmpty()) {
                continue;
            }
            byte[] bytes = encode(change.content().orElseThrow());
            total += bytes.length;
            if (total > RepositoryPatch.MAX_BYTES) {
                throw new IllegalArgumentException("patch exceeds its byte limit");
            }
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
            return StrictText.utf8(source, ContentLimits.MAX_DOCUMENT_BYTES, "text exceeds its byte limit");
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
                if (entry == null) {
                    throw new RepositoryConflictException("expected file no longer exists");
                }
                FileMode mode = entry.getFileMode();
                if (!RepositoryBlobs.isFile(mode)) {
                    throw new IllegalArgumentException("patch target must be a regular text file");
                }
                ObjectLoader blob = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
                if (blob.getSize() > ContentLimits.MAX_DOCUMENT_BYTES) {
                    throw new IllegalArgumentException("patch target exceeds its byte limit");
                }
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
}
