package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentConflictException;
import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentNotFoundException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.DocumentVisibility;
import io.github.core607.poketto.content.DocumentWriteResult;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWriteServiceTests {

    private static final WritePrincipal AGENT =
            new WritePrincipal(PrincipalType.API_KEY, "key-01HQ8");

    @TempDir
    Path dataDirectory;

    private WorkspacePaths paths;
    private ContentRepositoryStore store;
    private DocumentWriteService writes;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() {
        paths = new WorkspacePaths(dataDirectory.toAbsolutePath());
        CanonicalDocumentCodec codec = new CanonicalDocumentCodec();
        store = new JGitContentRepositoryStore(paths, codec);
        writes = new JGitDocumentWriteService(paths, codec, store, new TestClock());
        workspace = WorkspaceId.random();
    }

    @Test
    void createsTheRootCommitOnAnUnbornMain() throws Exception {
        DocumentWriteResult result = writes.create(workspace, AGENT, draft("documents/note.md"));

        assertThat(result.committed()).isTrue();
        assertThat(result.repositoryPath()).isEqualTo("documents/note.md");
        assertThat(result.revision()).isPresent();
        assertThat(headCommit(workspace).getParentCount()).isZero();

        StoredDocument stored = only(workspace);
        assertThat(stored.content().metadata().id()).isEqualTo(result.documentId());
        assertThat(stored.content().metadata().visibility()).isEqualTo(DocumentVisibility.PRIVATE);
        assertThat(stored.content().metadata().publishedAt()).isEmpty();
        assertThat(stored.content().metadata().createdAt())
                .isEqualTo(stored.content().metadata().updatedAt());
        assertThat(stored.revision()).isEqualTo(result.revision().orElseThrow());
        assertThat(worktreeFile(workspace, "documents/note.md")).exists();
    }

    @Test
    void refusesACreateOnAPathTakenAfterNormalizationAndCaseFolding() {
        writes.create(workspace, AGENT, draft("documents/Café.md"));

        assertThatThrownBy(() ->
                        writes.create(workspace, AGENT, draft("documents/CAFÉ.md")))
                .isInstanceOf(DocumentConflictException.class)
                .hasMessageContaining("documents/CAFÉ.md")
                .hasMessageContaining("documents/Café.md")
                .hasMessageContaining("Unicode normalization and case folding");
        assertThat(store.scan(workspace)).hasSize(1);
    }

    @Test
    void rejectsAnUnmanagedPathBeforeReachingTheRepository() {
        assertThatThrownBy(() -> writes.create(workspace, AGENT, draft("notes/loose.md")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documents/");
        assertThat(store.scan(workspace)).isEmpty();
    }

    @Test
    void advancesTheRevisionAndUpdateTimeWhenBytesChange() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        StoredDocument before = only(workspace);

        DocumentWriteResult updated = writes.update(
                workspace,
                AGENT,
                created.documentId(),
                created.revision().orElseThrow(),
                new DocumentDraft("documents/note.md", "Renamed", List.of("a"), "New body"));

        assertThat(updated.committed()).isTrue();
        assertThat(updated.revision()).isNotEqualTo(created.revision());
        StoredDocument after = only(workspace);
        assertThat(after.content().metadata().title()).isEqualTo("Renamed");
        assertThat(after.content().metadata().tags()).containsExactly("a");
        assertThat(after.content().body()).isEqualTo("New body");
        assertThat(after.content().metadata().updatedAt())
                .isAfter(before.content().metadata().updatedAt());
        assertThat(after.content().metadata().createdAt())
                .isEqualTo(before.content().metadata().createdAt());
    }

    @Test
    void acknowledgesAnUpdateThatChangesNeitherBytesNorPathWithoutANewCommit() throws Exception {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        String rootCommit = headCommit(workspace).name();

        DocumentWriteResult repeated = writes.update(
                workspace,
                AGENT,
                created.documentId(),
                created.revision().orElseThrow(),
                draft("documents/note.md"));

        assertThat(repeated.committed()).isFalse();
        assertThat(repeated.commitId()).isEqualTo(rootCommit);
        assertThat(repeated.revision()).isEqualTo(created.revision());
        assertThat(headCommit(workspace).name()).isEqualTo(rootCommit);
    }

    @Test
    void treatsAMoveAsAnEditThatEarnsANewRevision() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        StoredDocument before = only(workspace);

        DocumentWriteResult moved = writes.update(
                workspace,
                AGENT,
                created.documentId(),
                created.revision().orElseThrow(),
                draft("documents/archive/note.md"));

        assertThat(moved.committed()).isTrue();
        assertThat(moved.repositoryPath()).isEqualTo("documents/archive/note.md");
        assertThat(moved.revision()).isNotEqualTo(created.revision());
        StoredDocument after = only(workspace);
        assertThat(after.repositoryPath()).isEqualTo("documents/archive/note.md");
        assertThat(after.content().metadata().updatedAt())
                .isAfter(before.content().metadata().updatedAt());
        assertThat(worktreeFile(workspace, "documents/note.md")).doesNotExist();
        assertThat(worktreeFile(workspace, "documents/archive/note.md")).exists();
    }

    @Test
    void returnsAConflictCarryingTheLiveRevisionAfterAConcurrentMove() throws Exception {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        DocumentRevision stale = created.revision().orElseThrow();
        DocumentWriteResult moved = writes.update(
                workspace, AGENT, created.documentId(), stale, draft("documents/moved.md"));
        String liveCommit = headCommit(workspace).name();

        assertThatExceptionOfType(DocumentConflictException.class)
                .isThrownBy(() -> writes.update(
                        workspace,
                        AGENT,
                        created.documentId(),
                        stale,
                        draft("documents/note.md")))
                .satisfies(conflict -> assertThat(conflict.liveRevision())
                        .contains(moved.revision().orElseThrow()));
        assertThat(headCommit(workspace).name()).isEqualTo(liveCommit);
        assertThat(only(workspace).repositoryPath()).isEqualTo("documents/moved.md");
    }

    @Test
    void distinguishesAMissingDocumentFromAConflict() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        DocumentId absent = DocumentId.random();

        assertThatThrownBy(() -> writes.update(
                        workspace,
                        AGENT,
                        absent,
                        created.revision().orElseThrow(),
                        draft("documents/note.md")))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining(absent.toString());
    }

    @Test
    void refusesAMoveOntoAnOccupiedPath() {
        DocumentWriteResult first = writes.create(workspace, AGENT, draft("documents/first.md"));
        writes.create(workspace, AGENT, draft("documents/Second.md"));

        assertThatThrownBy(() -> writes.update(
                        workspace,
                        AGENT,
                        first.documentId(),
                        first.revision().orElseThrow(),
                        draft("documents/second.md")))
                .isInstanceOf(DocumentConflictException.class)
                .hasMessageContaining("documents/Second.md");
        assertThat(store.scan(workspace)).hasSize(2);
    }

    @Test
    void removesTheDocumentAndReportsNoRevision() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/gone.md"));

        DocumentWriteResult deleted = writes.delete(
                workspace, AGENT, created.documentId(), created.revision().orElseThrow());

        assertThat(deleted.committed()).isTrue();
        assertThat(deleted.repositoryPath()).isEqualTo("documents/gone.md");
        assertThat(deleted.revision()).isEmpty();
        assertThat(store.scan(workspace)).isEmpty();
        assertThat(worktreeFile(workspace, "documents/gone.md")).doesNotExist();
    }

    @Test
    void readsARetriedDeleteAsAlreadyApplied() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/gone.md"));
        DocumentRevision revision = created.revision().orElseThrow();
        writes.delete(workspace, AGENT, created.documentId(), revision);

        assertThatThrownBy(
                        () -> writes.delete(workspace, AGENT, created.documentId(), revision))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void setsThePublicationTimeOnTheFirstPublishOnly() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/post.md"));

        DocumentWriteResult published = writes.publish(
                workspace, AGENT, created.documentId(), created.revision().orElseThrow());

        StoredDocument afterPublish = only(workspace);
        assertThat(published.committed()).isTrue();
        assertThat(afterPublish.content().metadata().visibility())
                .isEqualTo(DocumentVisibility.PUBLIC);
        assertThat(afterPublish.content().metadata().publishedAt()).isPresent();

        DocumentWriteResult edited = writes.update(
                workspace,
                AGENT,
                created.documentId(),
                published.revision().orElseThrow(),
                new DocumentDraft("documents/post.md", "Edited", List.of(), "More"));
        StoredDocument afterEdit = only(workspace);

        assertThat(edited.committed()).isTrue();
        assertThat(afterEdit.content().metadata().publishedAt())
                .isEqualTo(afterPublish.content().metadata().publishedAt());
        assertThat(afterEdit.content().metadata().visibility())
                .isEqualTo(DocumentVisibility.PUBLIC);
    }

    @Test
    void acknowledgesARepublishAtTheLiveRevisionWithoutANewCommit() throws Exception {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/post.md"));
        DocumentWriteResult published = writes.publish(
                workspace, AGENT, created.documentId(), created.revision().orElseThrow());
        String publishCommit = headCommit(workspace).name();

        DocumentWriteResult republished = writes.publish(
                workspace, AGENT, created.documentId(), published.revision().orElseThrow());

        assertThat(republished.committed()).isFalse();
        assertThat(republished.commitId()).isEqualTo(publishCommit);
        assertThat(republished.revision()).isEqualTo(published.revision());
        assertThat(headCommit(workspace).name()).isEqualTo(publishCommit);
    }

    @Test
    void returnsAConflictWhenAPublishIsRetriedWithThePrePublishRevision() {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/post.md"));
        DocumentRevision prePublish = created.revision().orElseThrow();
        writes.publish(workspace, AGENT, created.documentId(), prePublish);

        assertThatThrownBy(
                        () -> writes.publish(workspace, AGENT, created.documentId(), prePublish))
                .isInstanceOf(DocumentConflictException.class);
        assertThat(only(workspace).content().metadata().visibility())
                .isEqualTo(DocumentVisibility.PUBLIC);
    }

    @Test
    void keepsRepositoriesIndependentAcrossWorkspaces() {
        WorkspaceId other = WorkspaceId.random();
        DocumentWriteResult here = writes.create(workspace, AGENT, draft("documents/note.md"));
        DocumentWriteResult there = writes.create(other, AGENT, draft("documents/note.md"));

        assertThat(here.documentId()).isNotEqualTo(there.documentId());

        writes.delete(workspace, AGENT, here.documentId(), here.revision().orElseThrow());

        assertThat(store.scan(workspace)).isEmpty();
        assertThat(only(other).content().metadata().id()).isEqualTo(there.documentId());
        assertThatThrownBy(() -> writes.delete(
                        workspace, AGENT, there.documentId(), there.revision().orElseThrow()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void commitsUnderAServiceIdentityWithOnlyAnOpaquePrincipalTrailer() throws Exception {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        RevCommit commit = headCommit(workspace);

        assertThat(commit.getAuthorIdent().getName()).isEqualTo("Poketto");
        assertThat(commit.getAuthorIdent().getEmailAddress()).isEqualTo("poketto@invalid");
        assertThat(commit.getCommitterIdent().getName()).isEqualTo("Poketto");
        assertThat(commit.getFullMessage())
                .isEqualTo("create " + created.documentId() + "\n\n"
                        + "Poketto-Principal: api-key:key-01HQ8\n");
        assertThat(commit.getShortMessage()).isEqualTo("create " + created.documentId());
    }

    @Test
    void namesTheOperationAndDocumentInEveryCommitSubject() throws Exception {
        DocumentWriteResult created = writes.create(workspace, AGENT, draft("documents/note.md"));
        DocumentWriteResult published = writes.publish(
                workspace, AGENT, created.documentId(), created.revision().orElseThrow());
        assertThat(headCommit(workspace).getShortMessage())
                .isEqualTo("publish " + created.documentId());

        writes.delete(workspace, AGENT, created.documentId(), published.revision().orElseThrow());
        assertThat(headCommit(workspace).getShortMessage())
                .isEqualTo("delete " + created.documentId());
    }

    @Test
    void requiresAWorkspaceAPrincipalAndADraft() {
        assertThatThrownBy(() -> writes.create(null, AGENT, draft("documents/note.md")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writes.create(workspace, null, draft("documents/note.md")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writes.create(workspace, AGENT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> writes.publish(workspace, AGENT, DocumentId.random(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static DocumentDraft draft(String path) {
        return new DocumentDraft(path, "Note", List.of("first", "second"), "Body text");
    }

    private StoredDocument only(WorkspaceId workspaceId) {
        List<StoredDocument> documents = store.scan(workspaceId);
        assertThat(documents).hasSize(1);
        return documents.getFirst();
    }

    private Path worktreeFile(WorkspaceId workspaceId, String path) {
        return paths.contentDirectory(workspaceId).resolve(path);
    }

    private RevCommit headCommit(WorkspaceId workspaceId) throws Exception {
        Path directory = paths.contentDirectory(workspaceId);
        assertThat(Files.isDirectory(directory)).isTrue();
        try (Repository repository = new FileRepositoryBuilder()
                        .setWorkTree(directory.toFile())
                        .findGitDir(directory.toFile())
                        .build();
                RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(repository.resolve("refs/heads/main"));
        }
    }
}
