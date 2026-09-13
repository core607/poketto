package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryBaselineReaderTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private static final RepositoryBaselineLimits LIMITS =
            new RepositoryBaselineLimits(100, 4 * 1024 * 1024, Duration.ofSeconds(30));

    @Test
    void preservesTheSingleFileSemanticsForTextLinksDirectoriesAndManagedPaths() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var media = new RepositoryMediaIndex.Media(UUID.randomUUID(), "a".repeat(64), "image/png", 9);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(".poketto/publishing.yaml", bytes("enabled: true\nmode: public-root\n"));
        entries.put(
                RepositoryMediaIndex.PATH,
                new RepositoryMediaIndex(
                                Map.of("public/picture.png", media, "virtual/picture.png", media, "plain.txt", media))
                        .encode());
        entries.put("public/note.md", bytes("# 猫咪\r\n正文\r\n"));
        entries.put("plain.txt", bytes("exact text"));
        entries.put("private/bad.md", bytes("---\nnot: [valid\n---\n"));
        entries.put("invalid.bin", new byte[] {(byte) 0xc3, 0x28});
        entries.put("large.txt", new byte[ContentLimits.MAX_DOCUMENT_BYTES + 1]);
        entries.put("link", bytes("private/bad.md"));
        var commit = fixture.commitRemote(workspace, entries, Map.of("link", FileMode.SYMLINK));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var captured = new LinkedHashMap<String, RepositoryFile>();
        reader.visitBaseline(workspace, commit.name(), LIMITS, file -> {
            assertThat(captured.put(file.path(), file)).isNull();
        });
        assertThat(captured)
                .containsOnlyKeys(
                        ".poketto",
                        RepositoryMediaIndex.PATH,
                        ".poketto/publishing.yaml",
                        "public",
                        "public/note.md",
                        "public/picture.png",
                        "virtual/picture.png",
                        "plain.txt",
                        "private",
                        "private/bad.md",
                        "invalid.bin",
                        "large.txt",
                        "link");
        captured.forEach((path, file) ->
                assertThat(file).isEqualTo(reader.getFile(workspace, Optional.of(commit.name()), path)));
        assertThat(captured.get("plain.txt").source()).contains("exact text");
        assertThat(captured.get("link").expectedAbsence()).isFalse();
        assertThat(captured.get("public/picture.png").diagnostics())
                .extracting("code")
                .containsExactly("MANAGED_MEDIA");
        assertThat(fixture.cache(workspace).resolve("large.txt")).doesNotExist();
        assertThat(fixture.cache(workspace).resolve("link")).doesNotExist();
    }

    @Test
    void fetchesOnceAndKeepsThePinnedCommitWhenRemoteMainAdvancesDuringTraversal() throws Exception {
        var transport = spy(new JGitRemoteGitTransport());
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var commit = fixture.commitRemote(workspace, Map.of("one.md", bytes("old one"), "two.md", bytes("old two")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var captured = new ArrayList<RepositoryFile>();
        reader.visitBaseline(workspace, commit.name(), LIMITS, file -> {
            if (captured.isEmpty()) {
                assertThatCode(() -> fixture.commitRemote(
                                workspace, Map.of("one.md", bytes("new one"), "three.md", bytes("new three"))))
                        .doesNotThrowAnyException();
            }
            captured.add(file);
        });
        verify(transport, times(1)).fetchMain(any(), any());
        assertThat(captured).extracting(RepositoryFile::path).containsExactly("one.md", "two.md");
        assertThat(captured).allSatisfy(file -> assertThat(file.commit()).contains(commit.name()));
        assertThat(captured).extracting(file -> file.source().orElseThrow()).containsExactly("old one", "old two");
        assertThat(fixture.remoteHead(workspace)).isNotEqualTo(commit);
    }

    @Test
    void rejectsIncompleteTraversalForUtf8BytesEntryCapacityDeadlineAndSinkFailure() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(workspace, Map.of("a.md", bytes("猫"), "b.md", bytes("咪")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var captured = new ArrayList<RepositoryFile>();
        reader.visitBaseline(
                workspace, commit.name(), new RepositoryBaselineLimits(2, 6, Duration.ofSeconds(30)), captured::add);
        assertThat(captured).hasSize(2);
        captured.clear();
        assertThatThrownBy(() -> reader.visitBaseline(
                        workspace,
                        commit.name(),
                        new RepositoryBaselineLimits(2, 5, Duration.ofSeconds(30)),
                        captured::add))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("text byte limit");
        assertThat(captured).hasSize(1);
        assertThatThrownBy(() -> reader.visitBaseline(
                        workspace,
                        commit.name(),
                        new RepositoryBaselineLimits(1, 6, Duration.ofSeconds(30)),
                        file -> {}))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("entry limit");
        captured.clear();
        assertThatThrownBy(() -> reader.visitBaseline(
                        workspace,
                        commit.name(),
                        new RepositoryBaselineLimits(2, 6, Duration.ofNanos(1)),
                        captured::add))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("deadline");
        assertThat(captured).isEmpty();
        var failure = new IllegalStateException("private destination is full");
        assertThatThrownBy(() -> reader.visitBaseline(workspace, commit.name(), LIMITS, file -> {
                    throw failure;
                }))
                .isSameAs(failure);
    }

    @Test
    void rejectsAnUnreachableCommitBeforeEmittingAnyFiles() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, Map.of("one.md", bytes("text")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var captured = new ArrayList<RepositoryFile>();
        assertThatThrownBy(() -> reader.visitBaseline(workspace, "e".repeat(40), LIMITS, captured::add))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote main history");
        assertThat(captured).isEmpty();
    }

    @Test
    void checksPrivateReadBeforeTraversalAndAgainBeforeAcknowledgingStagedData() throws Exception {
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        var unavailable = mock(RepositoryContentReader.class);
        var denied = new AuthException(AuthException.Code.DENIED);
        doThrow(denied).when(auth).authorize(actor, workspace, Capability.READ_PRIVATE);
        assertThatThrownBy(() -> new AuthorizedRepositoryReader(auth, unavailable)
                        .visitBaseline(actor, workspace, "a".repeat(40), LIMITS, file -> {}))
                .isSameAs(denied);
        verifyNoInteractions(unavailable);

        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(workspace, Map.of("one.md", bytes("private text")));
        var current = mock(AuthService.class);
        var authorized = new AuthorizedRepositoryReader(current, new JGitRepositoryContentReader(fixture.authority()));
        var staged = new ArrayList<RepositoryFile>();
        assertThatThrownBy(() -> authorized.visitBaseline(actor, workspace, commit.name(), LIMITS, file -> {
                    staged.add(file);
                    doThrow(denied)
                            .when(current)
                            .withAuthorization(eq(actor), eq(workspace), eq(Set.of(Capability.READ_PRIVATE)), any());
                }))
                .isSameAs(denied);
        assertThat(staged).hasSize(1);
        verify(current).authorize(actor, workspace, Capability.READ_PRIVATE);
    }

    @Test
    void rejectsUnrepresentableAndOversizedGitPathsBeforeEmittingAliases() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var longPath = fixture.commitRemote(workspace, Map.of("a".repeat(256), bytes("text")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var captured = new ArrayList<RepositoryFile>();
        assertThatThrownBy(() -> reader.visitBaseline(workspace, longPath.name(), LIMITS, captured::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(captured).isEmpty();
        String invalidUtf8 = commitRawPath(fixture, new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> reader.visitBaseline(workspace, invalidUtf8, LIMITS, captured::add))
                .isInstanceOf(ContentRepositoryException.class)
                .hasRootCauseInstanceOf(CharacterCodingException.class);
        assertThat(captured).isEmpty();
    }

    private String commitRawPath(RemoteRepositoryFixture fixture, byte[] path) throws Exception {
        try (var repository = fixture.openRemote(workspace);
                var inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(path, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, bytes("private text")));
            var commit = new CommitBuilder();
            ObjectId previous = repository.resolve(Constants.R_HEADS + "main");
            commit.setParentId(previous);
            commit.setTreeId(inserter.insert(tree));
            var identity = new PersonIdent("Fixture", "fixture@invalid");
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("raw Git path\n");
            ObjectId next = inserter.insert(commit);
            inserter.flush();
            var update = repository.updateRef(Constants.R_HEADS + "main");
            update.setExpectedOldObjectId(previous);
            update.setNewObjectId(next);
            assertThat(update.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
            return next.name();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
