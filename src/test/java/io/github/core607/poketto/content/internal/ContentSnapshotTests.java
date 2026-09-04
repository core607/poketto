package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentSnapshot;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentSnapshotTests {

    private static final String FIRST_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String SECOND_ID = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";

    @TempDir
    Path root;

    @Test
    void servesTheValidatedSnapshotWithoutContactingTheRemote() throws Exception {
        SwitchableTransport transport = new SwitchableTransport();
        RemoteRepositoryFixture repositories = fixture("data", transport);
        WorkspaceId workspace = WorkspaceId.random();
        ObjectId commit = repositories.commitRemote(workspace, Map.of("documents/note.md", document(FIRST_ID, "Note")));

        repositories.store().ensureReady(workspace);
        transport.offline = true;

        ContentSnapshot snapshot = repositories.store().snapshot(workspace).orElseThrow();
        assertThat(snapshot.commitId()).contains(commit.name());
        assertThat(snapshot.documents()).hasSize(1);
        assertThat(snapshot.find(DocumentId.parse(FIRST_ID))).isPresent();
        assertThatThrownBy(() -> repositories.store().scan(workspace)).isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void aCommitThatFailsValidationLeavesTheServedSnapshotInPlace() throws Exception {
        RemoteRepositoryFixture repositories = fixture("data", new SwitchableTransport());
        WorkspaceId workspace = WorkspaceId.random();
        ObjectId good = repositories.commitRemote(workspace, Map.of("documents/note.md", document(FIRST_ID, "Note")));
        repositories.store().ensureReady(workspace);
        repositories.commitRemote(
                workspace, Map.of("documents/broken.md", "---\ntitle: [\n---\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> repositories.store().refresh(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("invalid document documents/broken.md");

        ContentSnapshot served = repositories.store().snapshot(workspace).orElseThrow();
        assertThat(served.commitId()).contains(good.name());
        assertThat(served.documents()).hasSize(1);

        ObjectId repaired = repositories.commitRemote(
                workspace,
                Map.of(
                        "documents/note.md",
                        document(FIRST_ID, "Note"),
                        "documents/broken.md",
                        document(SECOND_ID, "Repaired")));
        assertThat(repositories.store().refresh(workspace).commitId()).contains(repaired.name());
        assertThat(repositories.store().snapshot(workspace).orElseThrow().documents())
                .hasSize(2);
    }

    @Test
    void aDirectOwnerPushBecomesVisibleOnTheNextRefresh() throws Exception {
        RemoteRepositoryFixture repositories = fixture("data", new SwitchableTransport());
        WorkspaceId workspace = WorkspaceId.random();
        repositories.commitRemote(workspace, Map.of("documents/note.md", document(FIRST_ID, "Note")));
        repositories.store().ensureReady(workspace);

        repositories.commitRemote(
                workspace,
                Map.of(
                        "documents/note.md",
                        document(FIRST_ID, "Note"),
                        "documents/second.md",
                        document(SECOND_ID, "Second")));

        assertThat(repositories.store().snapshot(workspace).orElseThrow().documents())
                .hasSize(1);
        assertThat(repositories.store().refresh(workspace).documents()).hasSize(2);
    }

    @Test
    void aReplacementProcessServesTheLastValidatedCommitWhileTheRemoteIsUnreachable() throws Exception {
        RemoteRepositoryFixture first = fixture("data", new SwitchableTransport());
        WorkspaceId workspace = WorkspaceId.random();
        ObjectId validated = first.commitRemote(workspace, Map.of("documents/note.md", document(FIRST_ID, "Note")));
        first.store().ensureReady(workspace);
        // The newer push fails validation, so the cache is materialized at a commit that was
        // never served; the restart must fall back to the recorded commit, not the cache head.
        first.commitRemote(
                workspace,
                Map.of(
                        "documents/note.md",
                        document(FIRST_ID, "Note"),
                        "documents/second.md",
                        "not a document".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> first.store().refresh(workspace)).isInstanceOf(ContentRepositoryException.class);

        SwitchableTransport offline = new SwitchableTransport();
        offline.offline = true;
        RemoteRepositoryFixture replacement = fixture("data", offline);

        replacement.store().ensureReady(workspace);

        ContentSnapshot served = replacement.store().snapshot(workspace).orElseThrow();
        assertThat(served.commitId()).contains(validated.name());
        assertThat(served.documents()).hasSize(1);

        offline.offline = false;
        first.commitRemote(
                workspace,
                Map.of(
                        "documents/note.md",
                        document(FIRST_ID, "Note"),
                        "documents/second.md",
                        document(SECOND_ID, "Second")));
        assertThat(replacement.store().refresh(workspace).documents()).hasSize(2);
    }

    @Test
    void aReplacementProcessWithoutACacheFailsClosedWhileTheRemoteIsUnreachable() {
        SwitchableTransport offline = new SwitchableTransport();
        offline.offline = true;
        RemoteRepositoryFixture repositories = fixture("data", offline);
        WorkspaceId workspace = WorkspaceId.random();

        assertThatThrownBy(() -> repositories.store().ensureReady(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("remote repository fetch failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .satisfies(cause -> assertThat(cause.getMessage())
                                .contains("no validated commit is recorded in the cache")));
        assertThat(repositories.store().snapshot(workspace)).isEmpty();
    }

    @Test
    void rejectsWorkspaceBoundsBeforeMaterializingAnyManagedFile() throws Exception {
        RemoteRepositoryFixture repositories = fixture("data", new SwitchableTransport());
        WorkspaceId tooMany = WorkspaceId.random();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index <= ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE; index++) {
            entries.put("documents/note-" + index + ".md", document(FIRST_ID, "Note"));
        }
        repositories.commitRemote(tooMany, entries);

        assertThatThrownBy(() -> repositories.store().refresh(tooMany))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("managed documents exceed " + ContentLimits.MAX_DOCUMENTS_PER_WORKSPACE);
        assertThat(repositories.cache(tooMany).resolve("documents")).doesNotExist();

        WorkspaceId tooLarge = WorkspaceId.random();
        byte[] bytes = new byte[ContentLimits.MAX_DOCUMENT_BYTES];
        Arrays.fill(bytes, (byte) 'x');
        entries.clear();
        for (int index = 0; index <= ContentLimits.MAX_WORKSPACE_BYTES / bytes.length; index++) {
            entries.put("documents/note-" + index + ".md", bytes);
        }
        repositories.commitRemote(tooLarge, entries);

        assertThatThrownBy(() -> repositories.store().refresh(tooLarge))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("managed documents exceed " + ContentLimits.MAX_WORKSPACE_BYTES + " bytes");
        assertThat(repositories.cache(tooLarge).resolve("documents")).doesNotExist();
    }

    @Test
    void startupRestoresTheRecordedSnapshotWhenCurrentFrontmatterIsTooLarge() throws Exception {
        RemoteRepositoryFixture repositories = fixture("data", new SwitchableTransport());
        WorkspaceId workspace = WorkspaceId.random();
        ObjectId good = repositories.commitRemote(workspace, Map.of("documents/note.md", document(FIRST_ID, "Note")));
        repositories.store().ensureReady(workspace);
        String invalid = new String(document(FIRST_ID, "Note"), StandardCharsets.UTF_8)
                .replace("tags: []", "tags: []\n" + "# padding\n".repeat(2000));
        repositories.commitRemote(workspace, Map.of("documents/note.md", invalid.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> repositories.store().refresh(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("frontmatter must not exceed");
        RemoteRepositoryFixture replacement = fixture("data", new SwitchableTransport());
        replacement.store().ensureReady(workspace);
        assertThat(replacement.store().snapshot(workspace).orElseThrow().commitId())
                .contains(good.name());
    }

    @Test
    void aValidatedCommitEvictedFromTheCacheIsNotServed() throws Exception {
        RemoteRepositoryFixture first = fixture("data", new SwitchableTransport());
        WorkspaceId workspace = WorkspaceId.random();
        first.commitRemote(workspace, Map.of("documents/note.md", document(FIRST_ID, "Note")));
        first.store().ensureReady(workspace);
        try (Repository cache = JGitContentRepositoryStore.openCache(first.cache(workspace), workspace)) {
            Files.writeString(
                    cache.getDirectory().toPath().resolve("poketto-validated-main"),
                    "0123456789012345678901234567890123456789 2026-09-01T00:00:00Z\n");
        }
        SwitchableTransport offline = new SwitchableTransport();
        offline.offline = true;

        assertThatThrownBy(() -> fixture("data", offline).store().ensureReady(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()[0].getMessage())
                        .contains("last validated commit is no longer in the cache"));
    }

    @Test
    void rejectsAnOversizedDocumentWithoutServingIt() throws Exception {
        RemoteRepositoryFixture repositories = fixture("data", new SwitchableTransport());
        WorkspaceId workspace = WorkspaceId.random();
        byte[] oversized = Arrays.copyOf(document(FIRST_ID, "Large"), ContentLimits.MAX_DOCUMENT_BYTES + 1);
        Arrays.fill(oversized, document(FIRST_ID, "Large").length, oversized.length, (byte) 'x');
        repositories.commitRemote(workspace, Map.of("documents/large.md", oversized));

        assertThatThrownBy(() -> repositories.store().ensureReady(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("documents/large.md")
                .hasMessageContaining("must not exceed " + ContentLimits.MAX_DOCUMENT_BYTES + " bytes");
        assertThat(repositories.store().snapshot(workspace)).isEmpty();
        assertThat(repositories.cache(workspace).resolve("documents/large.md")).doesNotExist();
    }

    private RemoteRepositoryFixture fixture(String dataDirectory, RemoteGitTransport transport) {
        return new RemoteRepositoryFixture(root.resolve(dataDirectory), root.resolve("remotes"), transport);
    }

    private static byte[] document(String id, String title) {
        return ("""
                ---
                id: %s
                title: %s
                visibility: public
                tags: []
                created_at: 2026-08-26T09:00:00Z
                updated_at: 2026-08-26T09:00:00Z
                published_at: 2026-08-26T09:00:00Z
                ---

                Body of %s
                """).formatted(id, title, title).getBytes(StandardCharsets.UTF_8);
    }

    /** Stands in for a remote that stops answering; the objects it already delivered remain. */
    private static final class SwitchableTransport implements RemoteGitTransport {

        private final RemoteGitTransport delegate = new JGitRemoteGitTransport();
        private volatile boolean offline;

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            if (offline) {
                throw new RemoteGitTransportException("fetch");
            }
            return delegate.fetchMain(repository, binding);
        }

        @Override
        public PushStatus pushMain(
                Repository repository, RepositoryBinding binding, ObjectId expectedCommit, ObjectId candidateCommit) {
            if (offline) {
                throw new RemoteGitTransportException("ref update");
            }
            return delegate.pushMain(repository, binding, expectedCommit, candidateCommit);
        }
    }
}
