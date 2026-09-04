package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryPatchServiceTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final AuthService auth = mock(AuthService.class);

    private JGitRepositoryPatchService service(
            RemoteRepositoryFixture fixture, BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installed) {
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(principal.subjectId()).thenReturn(UUID.fromString("bf562fc1-f15b-4adb-80d2-b0fdab1a568d"));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        return new JGitRepositoryPatchService(fixture.authority(), auth, Clock.systemUTC(), installed);
    }

    @Test
    void createsUnbornMainWithExactBytesAndAcknowledgedSnapshot() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        AtomicInteger installed = new AtomicInteger();
        var service = service(fixture, (id, snapshot) -> {
            assertThat(id).isEqualTo(workspace);
            assertThat(snapshot.commitId()).isPresent();
            installed.incrementAndGet();
        });
        String source = "\ufeff# 中文\r\n\r\n保持原样。\r\n";
        RepositoryPatchResult result = service.apply(
                principal, workspace, new RepositoryPatch(Optional.empty(), List.of(create("笔记/首页.md", source))));
        assertThat(result.committed()).isTrue();
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        var file = new JGitRepositoryContentReader(fixture.authority())
                .getFile(workspace, Optional.of(result.commit()), "笔记/首页.md");
        assertThat(file.source()).contains(source);
        assertThat(result.revisions().get("笔记/首页.md")).isEqualTo(file.revision());
        assertThat(fixture.cache(workspace).resolve("笔记/首页.md")).doesNotExist();
        assertThat(installed).hasValue(1);
        verify(auth)
                .withAuthorization(eq(principal), eq(workspace), eq(java.util.Set.of(Capability.WRITE_PRIVATE)), any());
        verify(auth, never()).authorize(principal, workspace, Capability.PUBLISH);
    }

    @Test
    void movesUpdatesAndDeletesAtomicallyWhilePreservingUntouchedObjects() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        String original = "---\ncustom: retained\n---\n# Original\n";
        byte[] image = new byte[2 * 1024 * 1024];
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of(
                        "private/source.md",
                        bytes(original),
                        "private/update.md",
                        bytes("# Before"),
                        "private/remove.md",
                        bytes("# Remove"),
                        "image.png",
                        image,
                        "documents/broken.md",
                        bytes("---\nbroken")));
        var result = service(fixture, (id, snapshot) -> {})
                .apply(
                        principal,
                        workspace,
                        patch(
                                base,
                                delete("private/source.md", original),
                                create("private/destination.md", original),
                                update("private/update.md", "# Before", "# After"),
                                delete("private/remove.md", "# Remove")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/source.md")
                        .expectedAbsence())
                .isTrue();
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/destination.md")
                        .source())
                .contains(original);
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/update.md")
                        .source())
                .contains("# After");
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/remove.md")
                        .expectedAbsence())
                .isTrue();
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "documents/broken.md")
                        .source())
                .contains("---\nbroken");
        assertThat(fixture.cache(workspace).resolve("image.png")).doesNotExist();
    }

    @Test
    void wrongRevisionAbsenceAndBaseNeverAdvanceRemote() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(workspace, Map.of("private/note.md", bytes("# Original")));
        var service = service(fixture, (id, snapshot) -> {});
        assertThatThrownBy(() ->
                        service.apply(principal, workspace, patch(base, update("private/note.md", "wrong", "# New"))))
                .isInstanceOf(RepositoryConflictException.class);
        assertThatThrownBy(() -> service.apply(principal, workspace, patch(base, create("private/note.md", "# New"))))
                .isInstanceOf(RepositoryConflictException.class);
        assertThatThrownBy(() -> service.apply(
                        principal,
                        workspace,
                        new RepositoryPatch(Optional.empty(), List.of(create("another.md", "# New")))))
                .isInstanceOf(RepositoryConflictException.class);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void aPatchCannotImplicitlyReplaceAnUncheckedDirectoryOrAncestor() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(
                workspace, Map.of("private/dir/child.md", bytes("# Child"), "private/file", bytes("text")));
        var service = service(fixture, (id, snapshot) -> {});
        assertThatThrownBy(() -> service.apply(principal, workspace, patch(base, create("private/dir", "text"))))
                .isInstanceOfAny(RepositoryConflictException.class, IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        service.apply(principal, workspace, patch(base, create("private/file/child.md", "# New"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void requiresPublishForPublicChangesAndPolicyChangesButNotExcludedText() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        String policy = "enabled: true\nmode: public-by-default\nexclude: ['drafts/**']\n";
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        bytes(policy),
                        "article.md",
                        bytes("# Article"),
                        "private/note.md",
                        bytes("# Private")));
        var service = service(fixture, (id, snapshot) -> {});
        doThrow(new SecurityException("publish denied")).when(auth).authorize(principal, workspace, Capability.PUBLISH);
        assertThatThrownBy(() -> service.apply(
                        principal, workspace, patch(base, update("article.md", "# Article", "# Changed"))))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.apply(
                        principal, workspace, patch(base, delete(RepositoryPublishingPolicy.PATH, policy))))
                .isInstanceOf(SecurityException.class);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
        var saved = service.apply(
                principal,
                workspace,
                patch(
                        base,
                        create("drafts/new.md", "# Draft"),
                        update("private/note.md", "# Private", "# Private update")));
        assertThat(saved.committed()).isTrue();
    }

    @Test
    void exactNoOpDoesNotCreateCommitOrReinstallSnapshot() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(workspace, Map.of("private/note.md", bytes("# Same\r\n")));
        AtomicInteger installed = new AtomicInteger();
        var result = service(fixture, (id, snapshot) -> installed.incrementAndGet())
                .apply(principal, workspace, patch(base, update("private/note.md", "# Same\r\n", "# Same\r\n")));
        assertThat(result.committed()).isFalse();
        assertThat(result.commit()).isEqualTo(base.name());
        assertThat(installed).hasValue(0);
    }

    @Test
    void lostPushResponseIsReconciledOnceWithoutMaterializingMalformedContent() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        var delegate = new JGitRemoteGitTransport();
        RemoteGitTransport transport = new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                return delegate.fetchMain(repository, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                pushes.incrementAndGet();
                delegate.pushMain(repository, binding, expected, candidate);
                throw new RemoteGitTransportException("simulated lost response");
            }
        };
        var fixture = new RemoteRepositoryFixture(directory, transport);
        ObjectId base = fixture.commitRemote(workspace, Map.of("documents/bad.md", bytes("---\nbad")));
        var result = service(fixture, (id, snapshot) -> {})
                .apply(principal, workspace, patch(base, create("private/new.md", "# New")));
        assertThat(result.committed()).isTrue();
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        assertThat(pushes).hasValue(1);
        assertThat(fixture.cache(workspace).resolve("documents/bad.md")).doesNotExist();
    }

    @Test
    void indeterminatePushNeverRetriesAndSnapshotFailurePreservesRemoteAcknowledgement() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        var delegate = new JGitRemoteGitTransport();
        var fixture = new RemoteRepositoryFixture(directory, new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                if (pushes.get() > 0) throw new RemoteGitTransportException("simulated offline");
                return delegate.fetchMain(repository, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                pushes.incrementAndGet();
                throw new RemoteGitTransportException("simulated lost response");
            }
        });
        assertThatThrownBy(() -> service(fixture, (id, snapshot) -> {})
                        .apply(
                                principal,
                                workspace,
                                new RepositoryPatch(Optional.empty(), List.of(create("private/new.md", "# New")))))
                .isInstanceOf(RepositoryWriteAmbiguousException.class)
                .hasMessageContaining("do not retry blindly");
        assertThat(pushes).hasValue(1);
        var second = new RemoteRepositoryFixture(directory.resolve("second"));
        var result = service(second, (id, snapshot) -> {
                    throw new IllegalStateException("disk failure");
                })
                .apply(
                        principal,
                        workspace,
                        new RepositoryPatch(Optional.empty(), List.of(create("private/new.md", "# New"))));
        assertThat(result.committed()).isTrue();
        assertThat(result.snapshotUpdated()).isFalse();
        assertThat(second.remoteHead(workspace)).isNotEqualTo(ObjectId.zeroId());
    }

    @Test
    void lostSuccessfulReplyWithLockedLocalRefStillReportsRemoteAcknowledgement() throws Exception {
        AtomicInteger pushes = new AtomicInteger();
        var delegate = new JGitRemoteGitTransport();
        RemoteGitTransport transport = new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                ObjectId resolved = delegate.fetchMain(repository, binding);
                if (pushes.get() > 0) {
                    try {
                        Path lock = repository.getDirectory().toPath().resolve("refs/heads/main.lock");
                        java.nio.file.Files.createDirectories(lock.getParent());
                        java.nio.file.Files.writeString(lock, "held by another local process");
                    } catch (java.io.IOException exception) {
                        throw new RuntimeException(exception);
                    }
                }
                return resolved;
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                pushes.incrementAndGet();
                delegate.pushMain(repository, binding, expected, candidate);
                throw new RemoteGitTransportException("simulated lost response");
            }
        };
        var fixture = new RemoteRepositoryFixture(directory, transport);
        assertThatThrownBy(() -> service(fixture, (id, snapshot) -> {})
                        .apply(
                                principal,
                                workspace,
                                new RepositoryPatch(Optional.empty(), List.of(create("private/new.md", "# New")))))
                .isInstanceOf(RepositoryWriteAmbiguousException.class)
                .hasMessageContaining("remote acknowledged");
        assertThat(pushes).hasValue(1);
        assertThat(fixture.remoteHead(workspace)).isNotEqualTo(ObjectId.zeroId());
    }

    @Test
    void rejectsBinaryImagesOversizedTextAndCasefoldedPathCollisions() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of(
                        "private/note.md",
                        bytes("# Original"),
                        "private/data",
                        new byte[] {(byte) 0xff},
                        "private/Folder",
                        bytes("text")));
        var service = service(fixture, (id, snapshot) -> {});
        assertThatThrownBy(() -> service.apply(
                        principal, workspace, patch(base, create("private/picture.png", "not a picture"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("images are read-only");
        assertThatThrownBy(() -> service.apply(
                        principal, workspace, patch(base, create("private/large.md", "x".repeat(1024 * 1024 + 1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.apply(
                        principal,
                        workspace,
                        patch(
                                base,
                                new RepositoryTextChange(
                                        "private/data",
                                        false,
                                        Optional.of(DocumentRevision.sha256(new byte[] {(byte) 0xff})),
                                        Optional.of("text")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() ->
                        service.apply(principal, workspace, patch(base, create("private/NOTE.md", "# Collision"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collision");
        assertThatThrownBy(() -> service.apply(
                        principal, workspace, patch(base, create("private/folder/child.md", "# Collision"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collision");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void twoEditorsWithTheSameBaseHaveOneWinner() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(workspace, Map.of("private/note.md", bytes("# Original")));
        var service = service(fixture, (id, snapshot) -> {});
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(
                    () -> attempt(service, patch(base, update("private/note.md", "# Original", "# A"))),
                    () -> attempt(service, patch(base, update("private/note.md", "# Original", "# B")))));
            assertThat(results.stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }))
                    .containsExactlyInAnyOrder("success", "conflict");
        }
    }

    private String attempt(JGitRepositoryPatchService service, RepositoryPatch patch) {
        try {
            service.apply(principal, workspace, patch);
            return "success";
        } catch (RepositoryConflictException exception) {
            return "conflict";
        }
    }

    private static RepositoryPatch patch(ObjectId base, RepositoryTextChange... changes) {
        return new RepositoryPatch(Optional.of(base.name()), List.of(changes));
    }

    private static RepositoryTextChange create(String path, String source) {
        return new RepositoryTextChange(path, true, Optional.empty(), Optional.of(source));
    }

    private static RepositoryTextChange update(String path, String before, String after) {
        return new RepositoryTextChange(
                path, false, Optional.of(DocumentRevision.sha256(bytes(before))), Optional.of(after));
    }

    private static RepositoryTextChange delete(String path, String before) {
        return new RepositoryTextChange(
                path, false, Optional.of(DocumentRevision.sha256(bytes(before))), Optional.empty());
    }

    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
