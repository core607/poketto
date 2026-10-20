package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
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
    void aRepeatedBaselineReusesDecodedFilesButStillResolvesTheAuthoritativeCommit() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var first = fixture.commitRemote(workspace, Map.of("note.md", bytes("---\ntitle: One\n---\nfirst")));
        var reads = new AtomicInteger();
        var authority = observedAuthority(fixture.authority(), reads);
        var reader = new JGitRepositoryContentReader(authority);
        var original = new ArrayList<RepositoryFile>();
        reader.visitBaseline(workspace, first.name(), LIMITS, original::add);
        int firstReads = reads.get();
        assertThat(firstReads).isPositive();
        var repeated = new ArrayList<RepositoryFile>();
        reader.visitBaseline(workspace, first.name(), LIMITS, repeated::add);
        assertThat(repeated).isEqualTo(original);
        assertThat(reads).hasValue(firstReads);

        var next = fixture.commitRemote(workspace, Map.of("note.md", bytes("---\ntitle: Two\n---\nsecond")));
        repeated.clear();
        reader.visitBaseline(workspace, next.name(), LIMITS, repeated::add);
        assertThat(reads.get()).isGreaterThan(firstReads);
        assertThat(repeated)
                .extracting(file -> file.source().orElseThrow())
                .containsExactly("---\ntitle: Two\n---\nsecond");

        var denied = new ContentRepositoryException("synthetic source unavailable");
        doThrow(denied).when(authority).readObjects(eq(workspace), any());
        assertThatThrownBy(() -> reader.visitBaseline(workspace, next.name(), LIMITS, file -> {}))
                .isSameAs(denied);
    }

    @Test
    void aFailedSinkDoesNotPublishAPartialBaseline() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(workspace, Map.of("a.md", bytes("one"), "b.md", bytes("two")));
        var reads = new AtomicInteger();
        var reader = new JGitRepositoryContentReader(observedAuthority(fixture.authority(), reads));
        var failure = new IllegalStateException("synthetic sink failure");
        assertThatThrownBy(() -> reader.visitBaseline(workspace, commit.name(), LIMITS, file -> {
                    throw failure;
                }))
                .isSameAs(failure);
        int failedReads = reads.get();
        var complete = new ArrayList<RepositoryFile>();
        reader.visitBaseline(workspace, commit.name(), LIMITS, complete::add);
        assertThat(complete).extracting(RepositoryFile::path).containsExactly("a.md", "b.md");
        assertThat(reads.get()).isGreaterThan(failedReads);
    }

    @Test
    void streamingManyFilesDoesNotReloadTheRootTreeForEveryEntry() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 128; i++) {
            files.put("notes/note-" + i + ".md", bytes("# Note " + i + "\nBody\n"));
        }
        var commit = fixture.commitRemote(workspace, files);
        try (var repository = fixture.openRemote(workspace);
                var objects = repository.newObjectReader()) {
            ObjectId rootTree = repository.resolve(commit.name() + "^{tree}");
            var rootReads = new AtomicInteger();
            var observed = mock(ObjectReader.class, delegatesTo(objects));
            doAnswer(invocation -> {
                        AnyObjectId object = invocation.getArgument(0);
                        int type = invocation.getArgument(1);
                        if (object.equals(rootTree)) {
                            rootReads.incrementAndGet();
                        }
                        return objects.open(object, type);
                    })
                    .when(observed)
                    .open(any(AnyObjectId.class), anyInt());
            var captured = new ArrayList<RepositoryFile>();
            new JGitRepositoryBaselineReader(
                            observed,
                            workspace,
                            commit.name(),
                            new RepositoryBaselineLimits(129, 4096, Duration.ofSeconds(30)),
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
                            captured::add)
                    .visit();
            assertThat(captured.stream().filter(file -> file.source().isPresent()))
                    .hasSize(128);
            assertThat(rootReads.get()).as("root tree reads for 128 files").isLessThan(10);
        }
    }

    @Test
    void aBlockedBaselineSinkPinsItsObjectsWithoutDelayingTheNextRemoteHeadRead() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, 1);
        var commit = fixture.commitRemote(workspace, Map.of("one.md", bytes("old one"), "two.md", bytes("old two")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        WorkspaceId other = WorkspaceId.random();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var reading = workers.submit(() -> {
                var captured = new ArrayList<RepositoryFile>();
                reader.visitBaseline(workspace, commit.name(), LIMITS, file -> {
                    if (captured.isEmpty()) {
                        entered.countDown();
                        await(release);
                    }
                    captured.add(file);
                });
                return captured;
            });
            try {
                await(entered);
                assertThatThrownBy(() -> fixture.authority().readObjects(other, snapshot -> snapshot.commitId()))
                        .isInstanceOf(ContentRepositoryException.class)
                        .hasMessageContaining("capacity is occupied by active or protected workspaces");
                var latest = fixture.commitRemote(workspace, Map.of("one.md", bytes("new one")));
                var remote = workers.submit(
                        () -> fixture.authority().readObjects(workspace, snapshot -> snapshot.commitId()));
                assertThat(remote.get(5, TimeUnit.SECONDS)).contains(latest.name());
                assertThat(reading).isNotDone();
            } finally {
                release.countDown();
            }
            assertThat(reading.get(5, TimeUnit.SECONDS))
                    .extracting(file -> file.source().orElseThrow())
                    .containsExactly("old one", "old two");
            fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
            assertThat(fixture.cache(workspace)).doesNotExist();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("baseline fixture interrupted", interrupted);
        }
    }

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
        var reader = new JGitRepositoryContentReader(fixture.authority());
        reader.visitBaseline(workspace, commit.name(), LIMITS, file -> {});
        var authorized = new AuthorizedRepositoryReader(current, reader);
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

    private RepositoryAuthority observedAuthority(RepositoryAuthority original, AtomicInteger reads) {
        var observed = mock(RepositoryAuthority.class, delegatesTo(original));
        doAnswer(invocation -> {
                    RepositoryAuthority.ObjectReaderAction<Object> action = invocation.getArgument(1);
                    return original.readImmutableObjects(workspace, objects -> {
                        var counted = mock(ObjectReader.class, delegatesTo(objects));
                        doAnswer(open -> {
                                    reads.incrementAndGet();
                                    AnyObjectId object = open.getArgument(0);
                                    int type = open.getArgument(1);
                                    return objects.open(object, type);
                                })
                                .when(counted)
                                .open(any(AnyObjectId.class), eq(Constants.OBJ_BLOB));
                        return action.read(counted);
                    });
                })
                .when(observed)
                .readImmutableObjects(eq(workspace), any());
        return observed;
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
