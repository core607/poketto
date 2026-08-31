package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentConflictException;
import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.DocumentWriteResult;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.RepositoryNotCleanException;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentWriteRecoveryTests {

    private static final WritePrincipal OWNER =
            new WritePrincipal(PrincipalType.ACCOUNT, "acct-7");
    private static final String INTENT_JOURNAL = ".git/poketto-write-intent";

    @TempDir
    Path dataDirectory;

    private WorkspacePaths paths;
    private CanonicalDocumentCodec codec;
    private ContentRepositoryStore store;
    private DocumentWriteService writes;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() {
        paths = new WorkspacePaths(dataDirectory.toAbsolutePath());
        codec = new CanonicalDocumentCodec();
        store = new JGitContentRepositoryStore(paths, codec);
        writes = new JGitDocumentWriteService(paths, codec, store, new TestClock());
        workspace = WorkspaceId.random();
    }

    @Test
    void blocksAMachineWriteWhenACommittedFileWasEditedInTheWorktree() throws Exception {
        writes.create(workspace, OWNER, draft("documents/note.md"));
        Files.writeString(contentFile("documents/note.md"), "hand edited");

        assertThatThrownBy(() -> writes.create(workspace, OWNER, draft("documents/other.md")))
                .isInstanceOf(RepositoryNotCleanException.class)
                .hasMessageContaining("must equal HEAD before a machine write")
                .hasMessageContaining("modified files")
                .hasMessageContaining("documents/note.md");
        assertThat(Files.readString(contentFile("documents/note.md"))).isEqualTo("hand edited");
    }

    @Test
    void blocksAMachineWriteOnUntrackedOperatorFiles() throws Exception {
        writes.create(workspace, OWNER, draft("documents/note.md"));
        Files.writeString(contentFile("documents/scratch.md"), "operator draft");

        assertThatThrownBy(() -> writes.create(workspace, OWNER, draft("documents/other.md")))
                .isInstanceOf(RepositoryNotCleanException.class)
                .hasMessageContaining("untracked files")
                .hasMessageContaining("documents/scratch.md");
    }

    @Test
    void blocksAMachineWriteOnIgnoredOperatorFiles() throws Exception {
        writes.create(workspace, OWNER, draft("documents/note.md"));
        Path exclude = contentFile(".git/info/exclude");
        Files.createDirectories(exclude.getParent());
        Files.writeString(exclude, "documents/ignored.md\n");
        Path ignored = contentFile("documents/ignored.md");
        Files.writeString(ignored, "operator draft");

        assertThatThrownBy(() -> writes.create(workspace, OWNER, draft("documents/ignored.md")))
                .isInstanceOf(RepositoryNotCleanException.class)
                .hasMessageContaining("ignored files")
                .hasMessageContaining("documents/ignored.md");
        assertThat(Files.readString(ignored)).isEqualTo("operator draft");
    }

    @Test
    void recoversJournaledPathsBeforeTheNextWrite() throws Exception {
        DocumentWriteResult created = writes.create(workspace, OWNER, draft("documents/note.md"));
        String committed = Files.readString(contentFile("documents/note.md"));

        // A crash after the worktree was mutated but before the commit leaves both behind.
        Files.writeString(contentFile("documents/note.md"), "half written bytes");
        Files.writeString(contentFile(INTENT_JOURNAL), "documents/note.md\n");

        DocumentWriteResult next = writes.create(workspace, OWNER, draft("documents/second.md"));

        assertThat(next.committed()).isTrue();
        assertThat(Files.readString(contentFile("documents/note.md"))).isEqualTo(committed);
        assertThat(contentFile(INTENT_JOURNAL)).doesNotExist();
        assertThat(store.scan(workspace))
                .extracting(StoredDocument::repositoryPath)
                .containsExactly("documents/note.md", "documents/second.md");
        assertThat(store.scan(workspace).getFirst().revision())
                .isEqualTo(created.revision().orElseThrow());
    }

    @Test
    void removesAJournaledPathThatHeadNeverHeld() throws Exception {
        writes.create(workspace, OWNER, draft("documents/note.md"));
        Files.writeString(contentFile("documents/partial.md"), "orphaned by a crash");
        Files.writeString(contentFile(INTENT_JOURNAL), "documents/partial.md\n");

        writes.create(workspace, OWNER, draft("documents/second.md"));

        assertThat(contentFile("documents/partial.md")).doesNotExist();
        assertThat(contentFile(INTENT_JOURNAL)).doesNotExist();
    }

    @Test
    void leavesOperatorEditsOutsideTheJournalAlone() throws Exception {
        writes.create(workspace, OWNER, draft("documents/machine.md"));
        writes.create(workspace, OWNER, draft("documents/human.md"));
        String committed = Files.readString(contentFile("documents/machine.md"));

        Files.writeString(contentFile("documents/machine.md"), "half written bytes");
        Files.writeString(contentFile("documents/human.md"), "the owner was mid-sentence");
        Files.writeString(contentFile(INTENT_JOURNAL), "documents/machine.md\n");

        assertThatThrownBy(() -> writes.create(workspace, OWNER, draft("documents/third.md")))
                .isInstanceOf(RepositoryNotCleanException.class)
                .hasMessageContaining("documents/human.md")
                .hasMessageNotContaining("documents/machine.md");
        assertThat(Files.readString(contentFile("documents/machine.md"))).isEqualTo(committed);
        assertThat(Files.readString(contentFile("documents/human.md")))
                .isEqualTo("the owner was mid-sentence");
    }

    @Test
    void rollsBackAndClearsTheJournalWhenApplyingFails() throws Exception {
        writes.create(workspace, OWNER, draft("documents/note.md"));
        String committed = Files.readString(contentFile("documents/note.md"));
        String head = headCommitId();

        // documents/note.md is a file, so no document can be written below it.
        assertThatThrownBy(
                        () -> writes.create(workspace, OWNER, draft("documents/note.md/child.md")))
                .isInstanceOf(ContentRepositoryException.class);

        assertThat(contentFile(INTENT_JOURNAL)).doesNotExist();
        assertThat(Files.readString(contentFile("documents/note.md"))).isEqualTo(committed);
        assertThat(headCommitId()).isEqualTo(head);
        assertThat(store.scan(workspace)).hasSize(1);
        assertThat(writes.create(workspace, OWNER, draft("documents/second.md")).committed())
                .isTrue();
    }

    @Test
    void appliesALostAcknowledgementExactlyOnce() throws Exception {
        DocumentWriteResult created = writes.create(workspace, OWNER, draft("documents/note.md"));
        // The commit succeeded but its acknowledgement never reached the caller.
        writes.update(
                workspace,
                OWNER,
                created.documentId(),
                created.revision().orElseThrow(),
                new DocumentDraft("documents/note.md", "Applied", List.of(), "Once"));

        assertThatThrownBy(() -> writes.update(
                        workspace,
                        OWNER,
                        created.documentId(),
                        created.revision().orElseThrow(),
                        new DocumentDraft("documents/note.md", "Applied", List.of(), "Once")))
                .isInstanceOf(DocumentConflictException.class);

        assertThat(store.scan(workspace)).singleElement().satisfies(document ->
                assertThat(document.content().metadata().title()).isEqualTo("Applied"));
        assertThat(commitCount()).isEqualTo(2);
    }

    @Test
    void serializesConcurrentWritersToOneRepository() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentWriteService serialized = serviceThatParksInsideTheFirstScan(entered, release);
        CountDownLatch secondFinished = new CountDownLatch(1);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentWriteResult> first = threads.submit(
                    () -> serialized.create(workspace, OWNER, draft("documents/first.md")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<DocumentWriteResult> second = threads.submit(() -> {
                try {
                    return serialized.create(workspace, OWNER, draft("documents/second.md"));
                } finally {
                    secondFinished.countDown();
                }
            });

            assertThat(secondFinished.await(300, TimeUnit.MILLISECONDS))
                    .describedAs("a second writer must wait for the repository lock")
                    .isFalse();

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).committed()).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS).committed()).isTrue();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        assertThat(store.scan(workspace))
                .extracting(StoredDocument::repositoryPath)
                .containsExactly("documents/first.md", "documents/second.md");
        assertThat(commitCount()).isEqualTo(2);
    }

    @Test
    void letsWritersToTwoWorkspacesProceedIndependently() throws Exception {
        WorkspaceId other = WorkspaceId.random();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentWriteService parked = serviceThatParksInScansOf(workspace, entered, release);

        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<DocumentWriteResult> blocked = threads.submit(
                    () -> parked.create(workspace, OWNER, draft("documents/blocked.md")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            DocumentWriteResult independent =
                    parked.create(other, OWNER, draft("documents/independent.md"));

            assertThat(independent.committed()).isTrue();
            assertThat(blocked.isDone())
                    .describedAs("the parked workspace must still hold its own write")
                    .isFalse();

            release.countDown();
            assertThat(blocked.get(10, TimeUnit.SECONDS).committed()).isTrue();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        assertThat(store.scan(workspace))
                .extracting(StoredDocument::repositoryPath)
                .containsExactly("documents/blocked.md");
        assertThat(store.scan(other))
                .extracting(StoredDocument::repositoryPath)
                .containsExactly("documents/independent.md");
    }

    private DocumentWriteService serviceThatParksInsideTheFirstScan(
            CountDownLatch entered, CountDownLatch release) {
        AtomicBoolean parked = new AtomicBoolean();
        return serviceParkingWhen(workspaceId -> parked.compareAndSet(false, true), entered, release);
    }

    private DocumentWriteService serviceThatParksInScansOf(
            WorkspaceId target, CountDownLatch entered, CountDownLatch release) {
        AtomicBoolean parked = new AtomicBoolean();
        return serviceParkingWhen(
                workspaceId -> workspaceId.equals(target) && parked.compareAndSet(false, true),
                entered,
                release);
    }

    /**
     * Wraps the store so one scan parks while holding the repository lock the write already took.
     */
    private DocumentWriteService serviceParkingWhen(
            ParkDecision decision, CountDownLatch entered, CountDownLatch release) {
        ContentRepositoryStore parking = new ContentRepositoryStore() {

            @Override
            public void ensureReady(WorkspaceId workspaceId) {
                store.ensureReady(workspaceId);
            }

            @Override
            public List<StoredDocument> scan(WorkspaceId workspaceId) {
                if (decision.parks(workspaceId)) {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("parked scan was never released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                return store.scan(workspaceId);
            }
        };
        return new JGitDocumentWriteService(paths, codec, parking, new TestClock());
    }

    private static DocumentDraft draft(String path) {
        return new DocumentDraft(path, "Note", List.of(), "Body text");
    }

    private Path contentFile(String path) {
        return paths.contentDirectory(workspace).resolve(path);
    }

    private String headCommitId() throws Exception {
        try (Repository repository = open()) {
            return repository.resolve("refs/heads/main").name();
        }
    }

    private int commitCount() throws Exception {
        try (Repository repository = open();
                RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(repository.resolve("refs/heads/main")));
            int count = 0;
            while (walk.next() != null) {
                count++;
            }
            return count;
        }
    }

    private Repository open() throws Exception {
        Path directory = paths.contentDirectory(workspace);
        return new FileRepositoryBuilder()
                .setWorkTree(directory.toFile())
                .findGitDir(directory.toFile())
                .build();
    }

    @FunctionalInterface
    private interface ParkDecision {

        boolean parks(WorkspaceId workspaceId);
    }

}
