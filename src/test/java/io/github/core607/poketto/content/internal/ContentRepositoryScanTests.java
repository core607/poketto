package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentRepositoryScanTests {

    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";

    @TempDir
    Path dataDirectory;

    @Test
    void scansOnlyCommittedMarkdownBelowDocuments() throws Exception {
        Fixture fixture = fixture();
        byte[] committed = document(ID, "Committed", "Body");
        fixture.commit(Map.of(
                "README.md", "repository notes".getBytes(StandardCharsets.UTF_8),
                "documents/nested/note.md", committed));
        Path uncommitted = fixture.contentDirectory().resolve("documents/nested/note.md");
        Files.createDirectories(uncommitted.getParent());
        Files.writeString(uncommitted, "uncommitted working tree bytes");

        List<StoredDocument> documents = fixture.store().scan(fixture.workspaceId());

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.repositoryPath()).isEqualTo("documents/nested/note.md");
            assertThat(document.content().metadata().title()).isEqualTo("Committed");
            assertThat(document.content().body()).isEqualTo("Body");
            assertThat(document.revision()).isEqualTo(DocumentRevision.sha256(committed));
        });
    }

    @Test
    void keepsRepositoriesAndDocumentIdsIndependentAcrossWorkspaces() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(dataDirectory);
        Fixture first = fixture(repositories, WorkspaceId.random());
        Fixture second = fixture(repositories, WorkspaceId.random());
        first.commit(Map.of("documents/same.md", document(ID, "First", "One")));
        second.commit(Map.of("documents/same.md", document(ID, "Second", "Two")));

        assertThat(first.store().scan(first.workspaceId()))
                .extracting(document -> document.content().metadata().title())
                .containsExactly("First");
        assertThat(second.store().scan(second.workspaceId()))
                .extracting(document -> document.content().metadata().title())
                .containsExactly("Second");
        assertThat(first.contentDirectory()).isNotEqualTo(second.contentDirectory());
    }

    @Test
    void reportsEveryPathSharingADocumentId() throws Exception {
        Fixture fixture = fixture();
        fixture.commit(Map.of(
                "documents/first.md", document(ID, "First", "One"),
                "documents/second.md", document(ID, "Second", "Two")));

        assertThatThrownBy(() -> fixture.store().scan(fixture.workspaceId()))
                .hasMessageContaining("duplicate document ids")
                .hasMessageContaining("documents/first.md")
                .hasMessageContaining("documents/second.md");
    }

    @Test
    void reportsEveryCrossPlatformPathCollision() throws Exception {
        Fixture fixture = fixture();
        fixture.commit(Map.of(
                "documents/Café.md", document(ID, "First", "One"),
                "documents/CAFE\u0301.md", document(
                        "6ba7b810-9dad-41d1-80b4-00c04fd430c8", "Second", "Two")));

        assertThatThrownBy(() -> fixture.store().scan(fixture.workspaceId()))
                .hasMessageContaining("collide after Unicode normalization and case folding")
                .hasMessageContaining("documents/Café.md")
                .hasMessageContaining("documents/CAFE\u0301.md");
    }

    @Test
    void rejectsNonMarkdownFilesInsideTheManagedTree() throws Exception {
        Fixture fixture = fixture();
        fixture.commit(Map.of("documents/note.txt", "not markdown".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> fixture.store().scan(fixture.workspaceId()))
                .hasMessageContaining("documents/note.txt")
                .hasMessageContaining("lowercase .md");
    }

    private Fixture fixture() {
        return fixture(new RemoteRepositoryFixture(dataDirectory), WorkspaceId.random());
    }

    private static Fixture fixture(
            RemoteRepositoryFixture repositories, WorkspaceId workspaceId) {
        ContentRepositoryStore store = repositories.store();
        store.ensureReady(workspaceId);
        return new Fixture(
                repositories, store, workspaceId, repositories.cache(workspaceId));
    }

    private static byte[] document(String id, String title, String body) {
        return ("""
                ---
                id: %s
                title: %s
                visibility: private
                tags: []
                created_at: 2026-08-26T09:00:00Z
                updated_at: 2026-08-26T09:00:00Z
                ---

                %s
                """).formatted(id, title, body).getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            RemoteRepositoryFixture repositories,
            ContentRepositoryStore store,
            WorkspaceId workspaceId,
            Path contentDirectory) {

        void commit(Map<String, byte[]> entries) throws Exception {
            repositories.commitRemote(workspaceId, entries);
        }
    }
}
