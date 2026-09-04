package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentConflictException;
import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentMetadata;
import io.github.core607.poketto.content.DocumentNotFoundException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.DocumentVisibility;
import io.github.core607.poketto.content.DocumentWriteResult;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JGitDocumentWriteService implements DocumentWriteService {

    private static final Logger log = LoggerFactory.getLogger(JGitDocumentWriteService.class);
    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String PRINCIPAL_TRAILER = "Poketto-Principal";
    // Git requires an author, so the service commits under a fixed non-person identity and keeps
    // the caller in a trailer. The .invalid domain is reserved and routes nowhere.
    private static final String SERVICE_AUTHOR_NAME = "Poketto";
    private static final String SERVICE_AUTHOR_EMAIL = "poketto@invalid";
    // Title and tag rules belong to DocumentMetadata. Probing them needs an id and timestamps that
    // no document keeps, so these stand in for a draft that has not reached a repository yet.
    private static final DocumentId PROBE_ID = new DocumentId(new UUID(0L, 0L));

    private final RepositoryAuthority authority;
    private final CanonicalDocumentCodec codec;
    private final JGitContentRepositoryStore store;
    private final Clock clock;

    JGitDocumentWriteService(
            RepositoryAuthority authority,
            CanonicalDocumentCodec codec,
            JGitContentRepositoryStore store,
            Clock clock) {
        this.authority = Objects.requireNonNull(authority, "repository authority must not be null");
        this.codec = Objects.requireNonNull(codec, "document codec must not be null");
        this.store = Objects.requireNonNull(store, "content repository store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public DocumentWriteResult create(WorkspaceId workspaceId, WritePrincipal principal, DocumentDraft draft) {
        requirePrincipal(principal);
        String path = validated(draft);

        return inRepository(workspaceId, (repository, base) -> {
            List<StoredDocument> documents = base.documents();
            Instant now = clock.instant();
            DocumentId documentId = DocumentId.random();
            DocumentContent content = new DocumentContent(
                    new DocumentMetadata(
                            documentId,
                            draft.title(),
                            DocumentVisibility.PRIVATE,
                            draft.tags(),
                            now,
                            now,
                            Optional.empty()),
                    draft.body());
            byte[] bytes = codec.serialize(content);
            requirePathFree(documents, path, null);
            requireIdFree(documents, documentId);
            ObjectId commit =
                    commit(repository, base, principal, "create", documentId, now, Map.of(path, bytes), Set.of());
            return new DocumentWriteResult(
                    documentId, commit.name(), true, path, Optional.of(DocumentRevision.sha256(bytes)));
        });
    }

    @Override
    public DocumentWriteResult update(
            WorkspaceId workspaceId,
            WritePrincipal principal,
            DocumentId documentId,
            DocumentRevision expectedRevision,
            DocumentDraft draft) {
        requirePrincipal(principal);
        Objects.requireNonNull(documentId, "document id must not be null");
        Objects.requireNonNull(expectedRevision, "expected document revision must not be null");
        String path = validated(draft);

        return inRepository(workspaceId, (repository, base) -> {
            List<StoredDocument> documents = base.documents();
            StoredDocument current = require(documents, documentId);
            requireRevision(current, expectedRevision);
            DocumentMetadata before = current.content().metadata();
            Instant now = changeTime(before);
            DocumentContent candidate = new DocumentContent(
                    new DocumentMetadata(
                            before.id(),
                            draft.title(),
                            before.visibility(),
                            draft.tags(),
                            before.createdAt(),
                            before.updatedAt(),
                            before.publishedAt()),
                    draft.body());
            DocumentContent next = codec.update(current.content(), candidate, now);

            boolean pathChanged = !path.equals(current.repositoryPath());
            if (!pathChanged && unchangedBytes(next, current)) {
                return unchanged(repository, current);
            }
            requirePathFree(documents, path, current.repositoryPath());

            // A move is an edit, so a document that only relocates still earns a new revision.
            next = advanced(next, before.updatedAt(), now);
            byte[] bytes = codec.serialize(next);
            Set<String> removals = pathChanged ? Set.of(current.repositoryPath()) : Set.of();
            ObjectId commit =
                    commit(repository, base, principal, "update", documentId, now, Map.of(path, bytes), removals);
            return new DocumentWriteResult(
                    documentId, commit.name(), true, path, Optional.of(DocumentRevision.sha256(bytes)));
        });
    }

    @Override
    public DocumentWriteResult delete(
            WorkspaceId workspaceId,
            WritePrincipal principal,
            DocumentId documentId,
            DocumentRevision expectedRevision) {
        requirePrincipal(principal);
        Objects.requireNonNull(documentId, "document id must not be null");
        Objects.requireNonNull(expectedRevision, "expected document revision must not be null");

        return inRepository(workspaceId, (repository, base) -> {
            List<StoredDocument> documents = base.documents();
            StoredDocument current = require(documents, documentId);
            requireRevision(current, expectedRevision);
            String path = current.repositoryPath();
            ObjectId commit =
                    commit(repository, base, principal, "delete", documentId, clock.instant(), Map.of(), Set.of(path));
            return new DocumentWriteResult(documentId, commit.name(), true, path, Optional.empty());
        });
    }

    @Override
    public DocumentWriteResult publish(
            WorkspaceId workspaceId,
            WritePrincipal principal,
            DocumentId documentId,
            DocumentRevision expectedRevision) {
        requirePrincipal(principal);
        Objects.requireNonNull(documentId, "document id must not be null");
        Objects.requireNonNull(expectedRevision, "expected document revision must not be null");

        return inRepository(workspaceId, (repository, base) -> {
            List<StoredDocument> documents = base.documents();
            StoredDocument current = require(documents, documentId);
            requireRevision(current, expectedRevision);
            DocumentMetadata before = current.content().metadata();
            Instant now = changeTime(before);
            Instant publishedAt = before.publishedAt().orElse(now);
            DocumentContent candidate = new DocumentContent(
                    new DocumentMetadata(
                            before.id(),
                            before.title(),
                            DocumentVisibility.PUBLIC,
                            before.tags(),
                            before.createdAt(),
                            later(before.updatedAt(), publishedAt),
                            Optional.of(publishedAt)),
                    current.content().body());
            DocumentContent next = codec.update(current.content(), candidate, now);
            if (unchangedBytes(next, current)) {
                return unchanged(repository, current);
            }

            String path = current.repositoryPath();
            next = advanced(next, before.updatedAt(), now);
            byte[] bytes = codec.serialize(next);
            ObjectId commit =
                    commit(repository, base, principal, "publish", documentId, now, Map.of(path, bytes), Set.of());
            return new DocumentWriteResult(
                    documentId, commit.name(), true, path, Optional.of(DocumentRevision.sha256(bytes)));
        });
    }

    private DocumentWriteResult inRepository(WorkspaceId workspaceId, WriteAction action) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        return authority.write(workspaceId, (snapshot, advancer) -> {
            try (Repository repository = JGitContentRepositoryStore.openCache(snapshot.worktree(), workspaceId)) {
                ObjectId baseCommit =
                        snapshot.commitId().map(ObjectId::fromString).orElseGet(ObjectId::zeroId);
                DocumentWriteResult result =
                        action.apply(repository, store.scanTree(repository, baseCommit, workspaceId));
                if (result.committed()) {
                    advancer.advance(result.commitId());
                    serve(workspaceId, repository, snapshot.worktree(), result.commitId());
                }
                return result;
            }
        });
    }

    /**
     * Remote {@code main} equals the acknowledged commit and the workspace lock is still held,
     * so the commit becomes the served snapshot at once. The write is acknowledged even when
     * that fails: the next refresh installs the commit, or keeps reporting why it cannot.
     */
    private void serve(WorkspaceId workspaceId, Repository repository, Path worktree, String commitId) {
        try {
            store.install(
                    workspaceId,
                    new RepositoryAuthority.Snapshot(worktree, Optional.of(commitId)),
                    store.scan(repository, ObjectId.fromString(commitId), workspaceId));
        } catch (ContentRepositoryException exception) {
            log.warn(
                    "workspace {} acknowledged commit {} but the served snapshot could not be replaced: {}",
                    workspaceId,
                    commitId,
                    exception.getMessage());
        }
    }

    private ObjectId commit(
            Repository repository,
            JGitContentRepositoryStore.ScannedTree base,
            WritePrincipal principal,
            String operation,
            DocumentId documentId,
            Instant now,
            Map<String, byte[]> upserts,
            Set<String> deletions) {
        requireWorkspaceCapacity(base, upserts, deletions);
        Set<String> touched = new LinkedHashSet<>(upserts.keySet());
        touched.addAll(deletions);
        ContentWorktree.recordIntent(repository, touched);
        try {
            ContentWorktree.apply(repository, upserts, deletions);
            PersonIdent author = new PersonIdent(SERVICE_AUTHOR_NAME, SERVICE_AUTHOR_EMAIL, now, ZoneOffset.UTC);
            RevCommit commit = Git.wrap(repository)
                    .commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setSign(false)
                    .setMessage(operation + " " + documentId + "\n\n" + PRINCIPAL_TRAILER + ": "
                            + principal.trailerValue() + "\n")
                    .call();
            ContentWorktree.clearIntent(repository);
            return commit.getId();
        } catch (GitAPIException | RuntimeException exception) {
            rollbackQuietly(repository, exception);
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ContentRepositoryException("document " + documentId + " cannot be committed", exception);
        }
    }

    /**
     * Refuses a commit that would push the workspace past its document or byte bound, so the
     * remote never receives a tree the scan would reject. Replaced bytes are not credited back,
     * which errs on the side of refusing at the very edge of the byte bound.
     */
    private static void requireWorkspaceCapacity(
            JGitContentRepositoryStore.ScannedTree base, Map<String, byte[]> upserts, Set<String> deletions) {
        Set<String> present = new LinkedHashSet<>();
        for (StoredDocument document : base.documents()) {
            present.add(document.repositoryPath());
        }
        long count = base.documents().size();
        for (String path : deletions) {
            if (present.contains(path) && !upserts.containsKey(path)) {
                count--;
            }
        }
        long added = 0;
        for (Map.Entry<String, byte[]> upsert : upserts.entrySet()) {
            if (!present.contains(upsert.getKey())) {
                count++;
            }
            added += upsert.getValue().length;
        }
        if (count > ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE) {
            throw new IllegalArgumentException(
                    "workspace must not hold more than " + ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE + " documents");
        }
        if (base.totalBytes() + added > ContentLimits.MAX_WORKSPACE_BYTES) {
            throw new IllegalArgumentException(
                    "workspace documents must not exceed " + ContentLimits.MAX_WORKSPACE_BYTES + " bytes");
        }
    }

    private static void rollbackQuietly(Repository repository, Exception failure) {
        try {
            ContentWorktree.rollback(repository);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private DocumentWriteResult unchanged(Repository repository, StoredDocument current) {
        final ObjectId head;
        try {
            head = repository.resolve(MAIN);
        } catch (IOException exception) {
            throw new ContentRepositoryException("main cannot be resolved", exception);
        }
        if (head == null) {
            throw new ContentRepositoryException("main holds no commit for a stored document");
        }
        return new DocumentWriteResult(
                current.content().metadata().id(),
                head.name(),
                false,
                current.repositoryPath(),
                Optional.of(current.revision()));
    }

    private boolean unchangedBytes(DocumentContent next, StoredDocument current) {
        return DocumentRevision.sha256(codec.serialize(next)).equals(current.revision());
    }

    /**
     * The instant a change to an existing document is recorded at. It is read under the
     * repository lock and always follows the stored {@code updated_at}, so a stepped-back host
     * clock or a direct push stamped in the future cannot make a legitimate write fail.
     */
    private Instant changeTime(DocumentMetadata before) {
        Instant now = clock.instant();
        Instant earliest = before.updatedAt().plusMillis(1);
        return now.isAfter(earliest) ? now : earliest;
    }

    /**
     * Guarantees that a committed change carries a later {@code updated_at}, and therefore a new
     * revision, even when the change is a move or a canonical rewrite that leaves the fields alone.
     */
    private static DocumentContent advanced(DocumentContent next, Instant previousUpdatedAt, Instant now) {
        DocumentMetadata metadata = next.metadata();
        if (metadata.updatedAt().isAfter(previousUpdatedAt)) {
            return next;
        }
        return new DocumentContent(
                new DocumentMetadata(
                        metadata.id(),
                        metadata.title(),
                        metadata.visibility(),
                        metadata.tags(),
                        metadata.createdAt(),
                        now,
                        metadata.publishedAt()),
                next.body());
    }

    private static String validated(DocumentDraft draft) {
        Objects.requireNonNull(draft, "document draft must not be null");
        String path = DocumentPathRules.validate(draft.repositoryPath());
        new DocumentMetadata(
                PROBE_ID,
                draft.title(),
                DocumentVisibility.PRIVATE,
                draft.tags(),
                Instant.EPOCH,
                Instant.EPOCH,
                Optional.empty());
        return path;
    }

    private static void requirePrincipal(WritePrincipal principal) {
        Objects.requireNonNull(principal, "acting principal must not be null");
    }

    private static StoredDocument require(List<StoredDocument> documents, DocumentId documentId) {
        return documents.stream()
                .filter(document -> document.content().metadata().id().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new DocumentNotFoundException(
                        "no document with id " + documentId + " exists on main", documentId));
    }

    private static void requireRevision(StoredDocument current, DocumentRevision expected) {
        if (!current.revision().equals(expected)) {
            throw new DocumentConflictException(
                    "document " + current.content().metadata().id() + " at "
                            + current.repositoryPath() + " has revision " + current.revision()
                            + ", not the expected " + expected,
                    current.revision());
        }
    }

    private static void requireIdFree(List<StoredDocument> documents, DocumentId documentId) {
        documents.stream()
                .filter(document -> document.content().metadata().id().equals(documentId))
                .findFirst()
                .ifPresent(existing -> {
                    throw new DocumentConflictException(
                            "document id " + documentId + " already exists at " + existing.repositoryPath());
                });
    }

    private static void requirePathFree(List<StoredDocument> documents, String path, String ownPath) {
        String key = DocumentPathRules.collisionKey(path);
        for (StoredDocument document : documents) {
            if (document.repositoryPath().equals(ownPath)) {
                continue;
            }
            if (DocumentPathRules.collisionKey(document.repositoryPath()).equals(key)) {
                throw new DocumentConflictException("document path " + path + " collides with "
                        + document.repositoryPath() + " after Unicode normalization and case folding");
            }
        }
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    @FunctionalInterface
    private interface WriteAction {

        DocumentWriteResult apply(Repository repository, JGitContentRepositoryStore.ScannedTree base);
    }
}
