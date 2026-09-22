package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryHistoryPage;
import io.github.core607.poketto.content.RepositoryHistoryQuery;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryHistoryTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void mergeChangesUseTheFirstParentAndMetadataIsBoundedWithoutAuthorEmail() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId first = fixture.commitRemote(workspace, Map.of("note.md", bytes("first")));
        fixture.commitRemote(workspace, Map.of("note.md", bytes("branch middle")));
        ObjectId branch = fixture.commitRemote(workspace, Map.of("note.md", bytes("merged")));
        ObjectId merged = appendCommit(fixture, branch, List.of(first, branch), "😸".repeat(300));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        RepositoryHistoryPage page =
                reader.history(workspace, Optional.empty(), new RepositoryHistoryQuery("note.md", 0, 20));
        assertThat(page.entries())
                .extracting(RepositoryHistoryPage.Entry::commit)
                .containsExactly(merged.name(), first.name());
        RepositoryHistoryPage.Entry entry = page.entries().getFirst();
        assertThat(entry.subject().codePointCount(0, entry.subject().length())).isEqualTo(240);
        assertThat(entry.author().codePointCount(0, entry.author().length())).isEqualTo(120);
        assertThat(entry.author()).doesNotContain("private-author@invalid");
    }

    @Test
    void oversizedCommitMetadataFailsInsteadOfReturningAnIncompleteHistory() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId first = fixture.commitRemote(workspace, Map.of("note.md", bytes("first")));
        appendCommit(fixture, first, List.of(first), "x".repeat(1024 * 1024));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThatThrownBy(
                        () -> reader.history(workspace, Optional.empty(), new RepositoryHistoryQuery("note.md", 0, 20)))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("metadata exceeds");
    }

    private ObjectId appendCommit(
            RemoteRepositoryFixture fixture, ObjectId treeSource, List<ObjectId> parents, String message)
            throws Exception {
        try (Repository repository = fixture.openRemote(workspace);
                ObjectInserter inserter = repository.newObjectInserter();
                var walk = new RevWalk(repository)) {
            var commit = new CommitBuilder();
            commit.setTreeId(walk.parseCommit(treeSource).getTree());
            commit.setParentIds(parents);
            var author = new PersonIdent("猫".repeat(150), "private-author@invalid");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage(message);
            ObjectId result = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
            update.setExpectedOldObjectId(repository.resolve(Constants.R_HEADS + "main"));
            update.setNewObjectId(result);
            assertThat(update.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
            return result;
        }
    }

    @Test
    void pagesLiteralPathChangesIncludingDeletionAndKeepsItsPinnedHistory() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId added = fixture.commitRemote(workspace, Map.of("note.md", bytes("first")));
        fixture.commitRemote(workspace, Map.of("note.md", bytes("first"), "other.md", bytes("unrelated")));
        ObjectId changed = fixture.commitRemote(workspace, Map.of("note.md", bytes("second")));
        ObjectId deleted = fixture.commitRemote(workspace, Map.of("renamed.md", bytes("second")));
        ObjectId recreated = fixture.commitRemote(workspace, Map.of("note.md", bytes("third")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        RepositoryHistoryPage first =
                reader.history(workspace, Optional.empty(), new RepositoryHistoryQuery("note.md", 0, 2));
        assertThat(first.commit()).isEqualTo(recreated.name());
        assertThat(first.entries())
                .extracting(RepositoryHistoryPage.Entry::commit)
                .containsExactly(recreated.name(), deleted.name());
        assertThat(first.entries())
                .extracting(RepositoryHistoryPage.Entry::present)
                .containsExactly(true, false);
        assertThat(first.nextOffset()).isEqualTo(2);
        fixture.commitRemote(workspace, Map.of("note.md", bytes("later")));
        RepositoryHistoryPage next = reader.history(
                workspace, Optional.of(first.commit()), new RepositoryHistoryQuery("note.md", first.nextOffset(), 32));
        assertThat(next.entries())
                .extracting(RepositoryHistoryPage.Entry::commit)
                .containsExactly(changed.name(), added.name());
        assertThat(next.nextOffset()).isNull();
        assertThat(reader.getFile(workspace, Optional.of(added.name()), "note.md")
                        .source())
                .contains("first");
        assertThat(next.entries())
                .extracting(RepositoryHistoryPage.Entry::author)
                .containsOnly("Repository Owner");
    }

    @Test
    void emptyPagesStillAdvanceAcrossAnUnrelatedRun() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId original = fixture.commitRemote(workspace, Map.of("note.md", bytes("first")));
        for (int index = 0; index < 260; index++) {
            fixture.commitRemote(workspace, Map.of("note.md", bytes("first")));
        }
        var reader = new JGitRepositoryContentReader(fixture.authority());
        RepositoryHistoryPage first =
                reader.history(workspace, Optional.empty(), new RepositoryHistoryQuery("note.md", 0, 20));
        assertThat(first.entries()).isEmpty();
        assertThat(first.nextOffset()).isEqualTo(256);
        RepositoryHistoryPage next = reader.history(
                workspace, Optional.of(first.commit()), new RepositoryHistoryQuery("note.md", first.nextOffset(), 20));
        assertThat(next.entries())
                .extracting(RepositoryHistoryPage.Entry::commit)
                .containsExactly(original.name());
        assertThat(next.nextOffset()).isNull();
    }

    @Test
    void invalidContinuationsAndAnotherWorkspacesCommitAreRefused() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId commit = fixture.commitRemote(workspace, Map.of("note.md", bytes("private")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThatThrownBy(
                        () -> reader.history(workspace, Optional.empty(), new RepositoryHistoryQuery("note.md", 1, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned commit");
        assertThatThrownBy(() -> reader.history(
                        WorkspaceId.random(), Optional.of(commit.name()), new RepositoryHistoryQuery("note.md", 0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryHistoryQuery("../note.md", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullReadPermissionIsRequiredBeforeTraversalAndRecheckedAfterIt() {
        AuthService auth = mock(AuthService.class);
        RepositoryContentReader source = mock(RepositoryContentReader.class);
        AuthPrincipal actor = mock(AuthPrincipal.class);
        var reader = new AuthorizedRepositoryReader(auth, source);
        var query = new RepositoryHistoryQuery("public/note.md", 0, 20);
        doThrow(new SecurityException("private history denied"))
                .when(auth)
                .authorize(actor, workspace, Capability.READ_PRIVATE);
        assertThatThrownBy(() -> reader.history(actor, workspace, Optional.empty(), query))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(source);

        AuthService currentAuth = mock(AuthService.class);
        var current = new AuthorizedRepositoryReader(currentAuth, source);
        when(source.history(workspace, Optional.empty(), query))
                .thenReturn(new RepositoryHistoryPage("a".repeat(40), query.path(), List.of(), null));
        when(currentAuth.withAuthorization(eq(actor), eq(workspace), eq(Set.of(Capability.READ_PRIVATE)), any()))
                .thenThrow(new SecurityException("access revoked during fetch"));
        assertThatThrownBy(() -> current.history(actor, workspace, Optional.empty(), query))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("revoked");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
