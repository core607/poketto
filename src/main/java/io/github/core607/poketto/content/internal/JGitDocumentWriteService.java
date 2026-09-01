package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
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
import io.github.core607.poketto.content.RepositoryNotCleanException;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.io.IOException;
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
import java.util.concurrent.locks.Lock;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

final class JGitDocumentWriteService implements DocumentWriteService {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String PRINCIPAL_TRAILER = "Poketto-Principal";
    // Git requires an author, so the service commits under a fixed non-person identity and keeps
    // the caller in a trailer. The .invalid domain is reserved and routes nowhere.
    private static final String SERVICE_AUTHOR_NAME = "Poketto";
    private static final String SERVICE_AUTHOR_EMAIL = "poketto@invalid";
    // Title and tag rules belong to DocumentMetadata. Probing them needs an id and timestamps that
    // no document keeps, so these stand in for a draft that has not reached a repository yet.
    private static final DocumentId PROBE_ID = new DocumentId(new UUID(0L, 0L));

    private final WorkspacePaths paths;
    private final CanonicalDocumentCodec codec;
    private final ContentRepositoryStore store;
    private final GitWriteDurability durability;
    private final ContentRepositoryLocks repositoryLocks;
    private final Clock clock;

    JGitDocumentWriteService(
            WorkspacePaths paths,
            CanonicalDocumentCodec codec,
            ContentRepositoryStore store,
            Clock clock) {
        this(
                paths,
                codec,
                store,
                new LocalGitWriteDurability(),
                new ContentRepositoryLocks(),
                clock);
    }

    JGitDocumentWriteService(
            WorkspacePaths paths,
            CanonicalDocumentCodec codec,
            ContentRepositoryStore store,
            GitWriteDurability durability,
            ContentRepositoryLocks repositoryLocks,
            Clock clock) {
        this.paths = Objects.requireNonNull(paths, "workspace paths must not be null");
        this.codec = Objects.requireNonNull(codec, "document codec must not be null");
        this.store = Objects.requireNonNull(store, "content repository store must not be null");
        this.durability = Objects.requireNonNull(durability, "write durability must not be null");
        this.repositoryLocks =
                Objects.requireNonNull(repositoryLocks, "repository locks must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public DocumentWriteResult create(
            WorkspaceId workspaceId, WritePrincipal principal, DocumentDraft draft) {
        requirePrincipal(principal);
        String path = validated(draft);
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

        return inRepository(workspaceId, (repository, documents) -> {
            requirePathFree(documents, path, null);
            requireIdFree(documents, documentId);
            GitCommitOutcome commit = commit(
                    workspaceId,
                    repository,
                    principal,
                    "create",
                    documentId,
                    now,
                    Map.of(path, bytes),
                    Set.of());
            return new DocumentWriteResult(
                    documentId,
                    commit.commit().name(),
                    true,
                    commit.mirrored(),
                    path,
                    Optional.of(DocumentRevision.sha256(bytes)));
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
        Instant now = clock.instant();

        return inRepository(workspaceId, (repository, documents) -> {
            StoredDocument current = require(documents, documentId);
            requireRevision(current, expectedRevision);
            DocumentMetadata before = current.content().metadata();
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
            GitCommitOutcome commit = commit(
                    workspaceId,
                    repository,
                    principal,
                    "update",
                    documentId,
                    now,
                    Map.of(path, bytes),
                    removals);
            return new DocumentWriteResult(
                    documentId,
                    commit.commit().name(),
                    true,
                    commit.mirrored(),
                    path,
                    Optional.of(DocumentRevision.sha256(bytes)));
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
        Instant now = clock.instant();

        return inRepository(workspaceId, (repository, documents) -> {
            StoredDocument current = require(documents, documentId);
            requireRevision(current, expectedRevision);
            String path = current.repositoryPath();
            GitCommitOutcome commit = commit(
                    workspaceId,
                    repository,
                    principal,
                    "delete",
                    documentId,
                    now,
                    Map.of(),
                    Set.of(path));
            return new DocumentWriteResult(
                    documentId,
                    commit.commit().name(),
                    true,
                    commit.mirrored(),
                    path,
                    Optional.empty());
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
        Instant now = clock.instant();

        return inRepository(workspaceId, (repository, documents) -> {
            StoredDocument current = require(documents, documentId);
            requireRevision(current, expectedRevision);
            DocumentMetadata before = current.content().metadata();
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
            GitCommitOutcome commit = commit(
                    workspaceId,
                    repository,
                    principal,
                    "publish",
                    documentId,
                    now,
                    Map.of(path, bytes),
                    Set.of());
            return new DocumentWriteResult(
                    documentId,
                    commit.commit().name(),
                    true,
                    commit.mirrored(),
                    path,
                    Optional.of(DocumentRevision.sha256(bytes)));
        });
    }

    private DocumentWriteResult inRepository(WorkspaceId workspaceId, WriteAction action) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        store.ensureReady(workspaceId);
        Lock lock = repositoryLocks.forWorkspace(workspaceId);
        lock.lock();
        try (Repository repository =
                JGitContentRepositoryStore.openExisting(
                        paths.contentDirectory(workspaceId), workspaceId)) {
            // A journal left by an interrupted write is machine debris; dirty state without one is
            // the owner's own editing and must block instead.
            durability.beforeWrite(workspaceId, repository);
            ContentWorktree.rollback(repository);
            requireClean(repository, workspaceId);
            return action.apply(repository, store.scan(workspaceId));
        } finally {
            lock.unlock();
        }
    }

    private GitCommitOutcome commit(
            WorkspaceId workspaceId,
            Repository repository,
            WritePrincipal principal,
            String operation,
            DocumentId documentId,
            Instant now,
            Map<String, byte[]> upserts,
            Set<String> deletions) {
        Set<String> touched = new LinkedHashSet<>(upserts.keySet());
        touched.addAll(deletions);
        ContentWorktree.recordIntent(repository, touched);
        try {
            ContentWorktree.apply(repository, upserts, deletions);
            PersonIdent author = new PersonIdent(
                    SERVICE_AUTHOR_NAME, SERVICE_AUTHOR_EMAIL, now, ZoneOffset.UTC);
            GitCommitOutcome commit = durability.commit(
                    workspaceId,
                    repository,
                    author,
                    operation + " " + documentId + "\n\n"
                            + PRINCIPAL_TRAILER + ": " + principal.trailerValue() + "\n");
            ContentWorktree.clearIntent(repository);
            return commit;
        } catch (RuntimeException exception) {
            rollbackQuietly(repository, exception);
            throw exception;
        }
    }

    private static void rollbackQuietly(Repository repository, Exception failure) {
        try {
            ContentWorktree.rollback(repository);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void requireClean(Repository repository, WorkspaceId workspaceId) {
        ContentWorktree.describeUncleanState(repository).ifPresent(state -> {
            throw new RepositoryNotCleanException(
                    "workspace " + workspaceId + " content repository at "
                            + paths.contentDirectory(workspaceId)
                            + " must equal HEAD before a machine write; commit or revert " + state);
        });
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
                durability.isMirrored(repository, head),
                current.repositoryPath(),
                Optional.of(current.revision()));
    }

    private boolean unchangedBytes(DocumentContent next, StoredDocument current) {
        return DocumentRevision.sha256(codec.serialize(next)).equals(current.revision());
    }

    /**
     * Guarantees that a committed change carries a later {@code updated_at}, and therefore a new
     * revision, even when the change is a move or a canonical rewrite that leaves the fields alone.
     */
    private static DocumentContent advanced(
            DocumentContent next, Instant previousUpdatedAt, Instant now) {
        DocumentMetadata metadata = next.metadata();
        if (metadata.updatedAt().isAfter(previousUpdatedAt)) {
            return next;
        }
        if (!now.isAfter(previousUpdatedAt)) {
            throw new IllegalArgumentException(
                    "document updated_at must advance when serialized content changes");
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
                            "document id " + documentId + " already exists at "
                                    + existing.repositoryPath());
                });
    }

    private static void requirePathFree(
            List<StoredDocument> documents, String path, String ownPath) {
        String key = DocumentPathRules.collisionKey(path);
        for (StoredDocument document : documents) {
            if (document.repositoryPath().equals(ownPath)) {
                continue;
            }
            if (DocumentPathRules.collisionKey(document.repositoryPath()).equals(key)) {
                throw new DocumentConflictException(
                        "document path " + path + " collides with " + document.repositoryPath()
                                + " after Unicode normalization and case folding");
            }
        }
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    @FunctionalInterface
    private interface WriteAction {

        DocumentWriteResult apply(Repository repository, List<StoredDocument> documents);
    }
}
