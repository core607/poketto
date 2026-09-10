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
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMoveRequest;
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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class RepositoryPatchServiceTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final AuthService auth = mock(AuthService.class);
    private final io.github.core607.poketto.content.RepositoryMediaValidator mediaValidator =
            mock(io.github.core607.poketto.content.RepositoryMediaValidator.class);

    @Test
    void publicMovePreservesAnAlreadyIneligibleReferenceWithoutPublishingItsTarget() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of(
                        "public/note.md",
                        bytes("# Note\n[hidden](../private/hidden.md)"),
                        "private/hidden.md",
                        bytes("# Private"),
                        RepositoryPublishingPolicy.PATH,
                        bytes("enabled: true\nmode: public-by-default\n")));
        var result = service(fixture, (id, snapshot) -> {})
                .move(
                        principal,
                        workspace,
                        new RepositoryMoveRequest(base.name(), "public/note.md", "public/folder/note.md"));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), "public/folder/note.md")
                        .source())
                .contains("# Note\n[hidden](../../private/hidden.md)");
        assertThat(reader.getFile(workspace, Optional.empty(), "private/hidden.md")
                        .source())
                .contains("# Private");
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        verify(auth).authorize(principal, workspace, Capability.PUBLISH);
    }

    @Test
    void directoryMoveRejectsSymlinkObjectsWithoutAdvancingAuthority() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of("private/box/link", bytes("/outside/secret"), "private/box/note.md", bytes("# Note")),
                Map.of("private/box/link", org.eclipse.jgit.lib.FileMode.SYMLINK));
        assertThatThrownBy(() -> service(fixture, (id, snapshot) -> {})
                        .move(
                                principal,
                                workspace,
                                new RepositoryMoveRequest(base.name(), "private/box", "private/new")))
                .hasMessageContaining("symlinks or submodules");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void directoryMoveRepairsReferencesAndReusesLargeGitAndIndexedMediaInOneCommit() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var original = new RepositoryMediaIndex.Media(UUID.randomUUID(), "e".repeat(64), "application/pdf", 123);
        var source = new java.util.LinkedHashMap<String, byte[]>();
        source.put(
                "private/box/note.md",
                bytes("---\r\ntitle: Note\r\n---\r\n[outside](../other.md) ![media](scan.pdf)\r\n`../other.md`\r\n"));
        source.put("private/other.md", bytes("[note](box/note.md#part)\n\n[asset][pdf]\n\n[pdf]: box/scan.pdf\n"));
        source.put(
                RepositoryMediaIndex.PATH, new RepositoryMediaIndex(Map.of("private/box/scan.pdf", original)).encode());
        source.put("private/box/legacy.bin", new byte[2 * 1024 * 1024]);
        for (int i = 0; i < 100; i++) source.put("private/box/n" + i + ".md", bytes("# " + i));
        ObjectId base = fixture.commitRemote(workspace, source);
        AtomicInteger installed = new AtomicInteger();
        var service = service(fixture, (id, snapshot) -> installed.incrementAndGet());
        var result = service.move(
                principal, workspace, new RepositoryMoveRequest(base.name(), "private/box", "private/deeper/box"));
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        assertThat(installed).hasValue(1);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), "private/deeper/box/note.md")
                        .source())
                .contains(
                        "---\r\ntitle: Note\r\n---\r\n[outside](../../other.md) ![media](scan.pdf)\r\n`../other.md`\r\n");
        assertThat(reader.getFile(workspace, Optional.empty(), "private/other.md")
                        .source())
                .contains("[note](deeper/box/note.md#part)\n\n[asset][pdf]\n\n[pdf]: deeper/box/scan.pdf\n");
        var movedIndex =
                RepositoryMediaIndex.parse(reader.getFile(workspace, Optional.empty(), RepositoryMediaIndex.PATH)
                        .source()
                        .orElseThrow()
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(movedIndex.files()).containsExactlyEntriesOf(Map.of("private/deeper/box/scan.pdf", original));
        verify(mediaValidator).validate(workspace, List.of(original));
        verify(auth, never()).authorize(principal, workspace, Capability.PUBLISH);
        try (Repository repository = JGitContentRepositoryStore.openCache(fixture.cache(workspace), workspace);
                var walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
            var after = walk.parseCommit(ObjectId.fromString(result.commit()));
            assertThat(after.getParentCount()).isEqualTo(1);
            assertThat(after.getParent(0).getId()).isEqualTo(base);
            try (var beforeFile = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                            repository,
                            "private/box/legacy.bin",
                            walk.parseCommit(base).getTree());
                    var afterFile = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                            repository, "private/deeper/box/legacy.bin", after.getTree())) {
                assertThat(afterFile.getObjectId(0)).isEqualTo(beforeFile.getObjectId(0));
            }
            assertThat(org.eclipse.jgit.treewalk.TreeWalk.forPath(repository, "private/box/note.md", after.getTree()))
                    .isNull();
        }
    }

    @Test
    void publishingAFileDoesNotRelocatePrivateDependenciesAndFolderPublishRequiresAuthority() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var original = new RepositoryMediaIndex.Media(UUID.randomUUID(), "e".repeat(64), "image/png", 123);
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of(
                        "private/box/note.md",
                        bytes("# Note\n![photo](photo.png)"),
                        RepositoryMediaIndex.PATH,
                        new RepositoryMediaIndex(Map.of("private/box/photo.png", original)).encode(),
                        RepositoryPublishingPolicy.PATH,
                        bytes("enabled: true\nmode: public-by-default\n")));
        var service = service(fixture, (id, snapshot) -> {});
        assertThatThrownBy(() -> service.move(
                        principal,
                        workspace,
                        new RepositoryMoveRequest(base.name(), "private/box/note.md", "public/note.md")))
                .hasMessageContaining("public document referencing private");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
        doThrow(new IllegalStateException("publish denied"))
                .when(auth)
                .authorize(principal, workspace, Capability.PUBLISH);
        var folder = new RepositoryMoveRequest(base.name(), "private/box", "public/box");
        assertThatThrownBy(() -> service.move(principal, workspace, folder)).hasMessage("publish denied");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
        org.mockito.Mockito.doReturn(null).when(auth).authorize(principal, workspace, Capability.PUBLISH);
        var result = service.move(principal, workspace, folder);
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), "public/box/note.md")
                        .source())
                .contains("# Note\n![photo](photo.png)");
    }

    @Test
    void movesRejectOccupiedDestinationStaleBaseAndDanglingPublicReferencesWithoutPartialWrites() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        ObjectId base = fixture.commitRemote(
                workspace,
                Map.of(
                        "public/article.md",
                        bytes("[other](other.md)"),
                        "public/other.md",
                        bytes("# Other"),
                        "private/existing.md/item.md",
                        bytes("# Existing"),
                        RepositoryPublishingPolicy.PATH,
                        bytes("enabled: true\nmode: public-by-default\n")));
        var service = service(fixture, (id, snapshot) -> {});
        assertThatThrownBy(() -> service.move(
                        principal,
                        workspace,
                        new RepositoryMoveRequest(base.name(), "public/other.md", "private/other.md")))
                .hasMessageContaining("public document referencing private");
        assertThatThrownBy(() -> service.move(
                        principal, workspace, new RepositoryMoveRequest(base.name(), "public", "private/existing")))
                .hasMessageContaining("roots");
        assertThatThrownBy(() -> service.move(
                        principal,
                        workspace,
                        new RepositoryMoveRequest(base.name(), "public/article.md", "private/EXISTING.md")))
                .hasMessageContaining("destination already exists");
        assertThatThrownBy(() -> service.move(
                        principal,
                        workspace,
                        new RepositoryMoveRequest("a".repeat(40), "public/article.md", "public/new.md")))
                .isInstanceOf(RepositoryConflictException.class);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
    }

    @Test
    void corruptIndexAllowsInPlacePrivateTextMaintenanceButRepairRequiresPublish() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        byte[] broken = "{broken".getBytes(StandardCharsets.UTF_8);
        byte[] note = "Original".getBytes(StandardCharsets.UTF_8);
        var base = fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryMediaIndex.PATH,
                        broken,
                        "private/note.md",
                        note,
                        RepositoryPublishingPolicy.PATH,
                        "enabled: true\nmode: public-by-default\n".getBytes(StandardCharsets.UTF_8)));
        var service = service(fixture, (id, snapshot) -> {});
        doThrow(new IllegalStateException("publish denied"))
                .when(auth)
                .authorize(principal, workspace, Capability.PUBLISH);
        var result = service.apply(
                principal,
                workspace,
                new RepositoryPatch(
                        Optional.of(base.name()),
                        List.of(new RepositoryTextChange(
                                "private/note.md",
                                false,
                                Optional.of(DocumentRevision.sha256(note)),
                                Optional.of("Updated")))));
        verify(auth, never()).authorize(principal, workspace, Capability.PUBLISH);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), RepositoryMediaIndex.PATH)
                        .source())
                .contains("{broken");
        var repair = new RepositoryPatch(
                Optional.of(result.commit()),
                List.of(new RepositoryTextChange(
                        RepositoryMediaIndex.PATH,
                        false,
                        Optional.of(DocumentRevision.sha256(broken)),
                        Optional.of(new String(RepositoryMediaIndex.empty().encode(), StandardCharsets.UTF_8)))));
        assertThatThrownBy(() -> service.apply(principal, workspace, repair)).hasMessage("publish denied");
        var create = new RepositoryPatch(
                Optional.of(result.commit()),
                List.of(new RepositoryTextChange("private/new.md", true, Optional.empty(), Optional.of("New"))));
        assertThatThrownBy(() -> service.apply(principal, workspace, create))
                .hasMessage("repair the media index before structural or publication changes");
        org.mockito.Mockito.doReturn(null).when(auth).authorize(principal, workspace, Capability.PUBLISH);
        var repaired = service.apply(principal, workspace, repair);
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(repaired.commit());
        assertThat(reader.listDirectory(workspace, Optional.empty(), "private", 0, 100)
                        .entries())
                .hasSize(1);
    }

    @Test
    void mediaIndexAndTextSaveTogetherAndPublicMediaChangesRequirePublish() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        byte[] initialIndex = RepositoryMediaIndex.empty().encode();
        byte[] initialNote = "# Original".getBytes(StandardCharsets.UTF_8);
        var base = fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryMediaIndex.PATH,
                        initialIndex,
                        "private/note.md",
                        initialNote,
                        RepositoryPublishingPolicy.PATH,
                        "enabled: true\nmode: public-by-default\n".getBytes(StandardCharsets.UTF_8)));
        var service = service(fixture, (id, snapshot) -> {});
        var media = new RepositoryMediaIndex.Media(UUID.randomUUID(), "c".repeat(64), "application/pdf", 128);
        var privateIndex = new RepositoryMediaIndex(Map.of("private/source.pdf", media));
        doThrow(new IllegalStateException("publish denied"))
                .when(auth)
                .authorize(principal, workspace, Capability.PUBLISH);
        var result = service.apply(
                principal,
                workspace,
                new RepositoryPatch(
                        Optional.of(base.name()),
                        List.of(
                                new RepositoryTextChange(
                                        RepositoryMediaIndex.PATH,
                                        false,
                                        Optional.of(DocumentRevision.sha256(initialIndex)),
                                        Optional.of(new String(privateIndex.encode(), StandardCharsets.UTF_8))),
                                new RepositoryTextChange(
                                        "private/note.md",
                                        false,
                                        Optional.of(DocumentRevision.sha256(initialNote)),
                                        Optional.of("# Updated\n[Source](source.pdf)")))));
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        verify(mediaValidator).validate(workspace, List.of(media));
        verify(auth, never()).authorize(principal, workspace, Capability.PUBLISH);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), "private/note.md")
                        .source())
                .contains("# Updated\n[Source](source.pdf)");
        assertThat(reader.listDirectory(workspace, Optional.empty(), "private", 0, 100)
                        .entries())
                .extracting(io.github.core607.poketto.content.RepositoryDirectoryPage.Entry::path)
                .contains("private/source.pdf");
        var publicIndex = new RepositoryMediaIndex(Map.of("public/source.pdf", media));
        var publication = new RepositoryPatch(
                Optional.of(result.commit()),
                List.of(new RepositoryTextChange(
                        RepositoryMediaIndex.PATH,
                        false,
                        Optional.of(DocumentRevision.sha256(privateIndex.encode())),
                        Optional.of(new String(publicIndex.encode(), StandardCharsets.UTF_8)))));
        assertThatThrownBy(() -> service.apply(principal, workspace, publication))
                .hasMessage("publish denied");
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
    }

    @Test
    void invalidOriginalPreventsBothIndexAndTextFromAdvancingRemote() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var base =
                fixture.commitRemote(workspace, Map.of("private/note.md", "Original".getBytes(StandardCharsets.UTF_8)));
        var service = service(fixture, (id, snapshot) -> {});
        var media = new RepositoryMediaIndex.Media(UUID.randomUUID(), "d".repeat(64), "application/pdf", 128);
        var index = new RepositoryMediaIndex(Map.of("private/source.pdf", media));
        doThrow(new IllegalArgumentException("original belongs to another workspace"))
                .when(mediaValidator)
                .validate(eq(workspace), any());
        var patch = new RepositoryPatch(
                Optional.of(base.name()),
                List.of(
                        new RepositoryTextChange(
                                RepositoryMediaIndex.PATH,
                                true,
                                Optional.empty(),
                                Optional.of(new String(index.encode(), StandardCharsets.UTF_8))),
                        new RepositoryTextChange("private/new.md", true, Optional.empty(), Optional.of("A new note"))));
        assertThatThrownBy(() -> service.apply(principal, workspace, patch))
                .hasMessage("original belongs to another workspace");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), "private/new.md").expectedAbsence())
                .isTrue();
        assertThat(reader.getFile(workspace, Optional.empty(), RepositoryMediaIndex.PATH)
                        .expectedAbsence())
                .isTrue();
    }

    private JGitRepositoryPatchService service(
            RemoteRepositoryFixture fixture, BiConsumer<WorkspaceId, RepositoryAuthority.Snapshot> installed) {
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(principal.subjectId()).thenReturn(UUID.fromString("bf562fc1-f15b-4adb-80d2-b0fdab1a568d"));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        return new JGitRepositoryPatchService(
                fixture.authority(), auth, Clock.systemUTC(), installed, (id, snapshot) -> {}, mediaValidator);
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
                principal,
                workspace,
                new RepositoryPatch(Optional.empty(), List.of(create("笔记 空格%#/100%.md", source))));
        assertThat(result.committed()).isTrue();
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(result.commit());
        var file = new JGitRepositoryContentReader(fixture.authority())
                .getFile(workspace, Optional.of(result.commit()), "笔记 空格%#/100%.md");
        assertThat(file.source()).contains(source);
        assertThat(result.revisions().get("笔记 空格%#/100%.md")).isEqualTo(file.revision());
        assertThat(fixture.cache(workspace).resolve("笔记 空格%#/100%.md")).doesNotExist();
        assertThat(installed).hasValue(1);
        verify(auth)
                .withAuthorization(eq(principal), eq(workspace), eq(java.util.Set.of(Capability.WRITE_PRIVATE)), any());
        verify(auth, never()).authorize(principal, workspace, Capability.PUBLISH);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void atomicMoveToQuestionMarkFolderPreservesTextAndDistinctEncodedNames() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        String original = "\ufeff# 原文\r\n";
        var base = fixture.commitRemote(
                workspace,
                Map.of(
                        "source?.md",
                        bytes(original),
                        "literal%2Fname.md",
                        bytes("# Encoded"),
                        "literal/name.md",
                        bytes("# Segments")));
        String destination = "目录 空格%#?/index.md";
        var result = service(fixture, (id, snapshot) -> {})
                .apply(
                        principal,
                        workspace,
                        patch(
                                base,
                                delete("source?.md", original),
                                create(destination, original),
                                update("literal%2Fname.md", "# Encoded", "# Changed")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "source?.md")
                        .expectedAbsence())
                .isTrue();
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), destination)
                        .source())
                .contains(original);
        assertThat(reader.getFile(workspace, Optional.of(result.commit()), "literal/name.md")
                        .source())
                .contains("# Segments");
        assertThat(reader.readTree(workspace, Optional.of(result.commit())).documents())
                .extracting(document -> document.route())
                .containsExactlyInAnyOrder("/目录 空格%#?", "/literal%2Fname", "/literal/name");
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
    void recoveryChecksRemoteHistoryAndRetriesOnlyTheIdenticalRetainedCommit() throws Exception {
        for (boolean delivered : List.of(false, true)) {
            var offline = new java.util.concurrent.atomic.AtomicBoolean();
            var candidates = new java.util.ArrayList<ObjectId>();
            var delegate = new JGitRemoteGitTransport();
            var fixture = new RemoteRepositoryFixture(
                    directory.resolve(Boolean.toString(delivered)), new RemoteGitTransport() {
                        @Override
                        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                            if (offline.get()) throw new RemoteGitTransportException("offline after lost reply");
                            return delegate.fetchMain(repository, binding);
                        }

                        @Override
                        public PushStatus pushMain(
                                Repository repository,
                                RepositoryBinding binding,
                                ObjectId expected,
                                ObjectId candidate) {
                            candidates.add(candidate);
                            if (candidates.size() == 1) {
                                if (delivered) delegate.pushMain(repository, binding, expected, candidate);
                                offline.set(true);
                                throw new RemoteGitTransportException("lost reply");
                            }
                            return delegate.pushMain(repository, binding, expected, candidate);
                        }
                    });
            ObjectId base = fixture.commitRemote(workspace, Map.of("private/note.md", bytes("before")));
            var patch = patch(base, update("private/note.md", "before", "retained"));
            var service = service(fixture, (id, snapshot) -> {});
            var unknown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    RepositoryWriteAmbiguousException.class, () -> service.apply(principal, workspace, patch));
            assertThat(unknown).isNotNull();
            var retained = unknown.attempt().orElseThrow();
            assertThat(retained.commit()).isEqualTo(candidates.getFirst().name());
            offline.set(false);
            if (delivered) fixture.commitRemote(workspace, Map.of("private/note.md", bytes("later-remote-edit")));
            ObjectId beforeRecovery = fixture.remoteHead(workspace);
            var result = service.recover(principal, workspace, patch, retained);
            assertThat(result.commit()).isEqualTo(retained.commit());
            if (delivered) {
                assertThat(candidates).hasSize(1);
                assertThat(fixture.remoteHead(workspace)).isEqualTo(beforeRecovery);
            } else {
                assertThat(candidates).containsExactly(candidates.getFirst(), candidates.getFirst());
                assertThat(fixture.remoteHead(workspace).name()).isEqualTo(retained.commit());
            }
            // Repeating recovery observes the retained commit; it never creates another write.
            service.recover(principal, workspace, patch, retained);
            assertThat(candidates).hasSize(delivered ? 1 : 2);
        }
    }

    @Test
    void recoveryRejectsAlteredPatchAndDivergedRemoteWithoutPushing() throws Exception {
        var offline = new java.util.concurrent.atomic.AtomicBoolean();
        var pushes = new AtomicInteger();
        var delegate = new JGitRemoteGitTransport();
        var fixture = new RemoteRepositoryFixture(directory, new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                if (offline.get()) throw new RemoteGitTransportException("offline");
                return delegate.fetchMain(repository, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                pushes.incrementAndGet();
                offline.set(true);
                throw new RemoteGitTransportException("lost reply before push");
            }
        });
        ObjectId base = fixture.commitRemote(workspace, Map.of("private/note.md", bytes("before")));
        var patch = patch(base, update("private/note.md", "before", "retained"));
        var service = service(fixture, (id, snapshot) -> {});
        var unknown = org.assertj.core.api.Assertions.catchThrowableOfType(
                RepositoryWriteAmbiguousException.class, () -> service.apply(principal, workspace, patch));
        var retained = unknown.attempt().orElseThrow();
        offline.set(false);
        assertThatThrownBy(() -> service.recover(
                        principal,
                        workspace,
                        patch(base, update("private/note.md", "before", "changed-after-unknown")),
                        retained))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectId other = fixture.commitRemote(workspace, Map.of("private/note.md", bytes("competing")));
        assertThatThrownBy(() -> service.recover(principal, workspace, patch, retained))
                .isInstanceOf(RepositoryConflictException.class);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(other);
        assertThat(pushes).hasValue(1);
    }

    @Test
    void moveRecoveryPreservesTheOriginalCommitAndRejectsChangedDestinations() throws Exception {
        for (String outcome : List.of("not-delivered", "delivered", "diverged")) {
            boolean delivered = outcome.equals("delivered");
            var offline = new java.util.concurrent.atomic.AtomicBoolean();
            var candidates = new java.util.ArrayList<ObjectId>();
            var delegate = new JGitRemoteGitTransport();
            var fixture = new RemoteRepositoryFixture(directory.resolve(outcome), new RemoteGitTransport() {
                @Override
                public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                    if (offline.get()) throw new RemoteGitTransportException("offline after lost move reply");
                    return delegate.fetchMain(repository, binding);
                }

                @Override
                public PushStatus pushMain(
                        Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                    candidates.add(candidate);
                    if (candidates.size() == 1) {
                        if (delivered) delegate.pushMain(repository, binding, expected, candidate);
                        offline.set(true);
                        throw new RemoteGitTransportException("lost move reply");
                    }
                    return delegate.pushMain(repository, binding, expected, candidate);
                }
            });
            ObjectId base = fixture.commitRemote(
                    workspace,
                    Map.of(
                            "private/folder/note.md", bytes("# Note"),
                            "private/backlink.md", bytes("[note](folder/note.md)")));
            var request = new RepositoryMoveRequest(base.name(), "private/folder", "private/renamed");
            var service = service(fixture, (id, snapshot) -> {});
            var unknown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    RepositoryWriteAmbiguousException.class, () -> service.move(principal, workspace, request));
            assertThat(unknown).isNotNull();
            var retained = unknown.attempt().orElseThrow();
            offline.set(false);
            assertThatThrownBy(() -> service.recover(
                            principal,
                            workspace,
                            new RepositoryMoveRequest(base.name(), "private/folder", "private/other"),
                            retained))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(candidates).hasSize(1);
            if (outcome.equals("diverged")) {
                ObjectId competing =
                        fixture.commitRemote(workspace, Map.of("private/competing.md", bytes("competing")));
                assertThatThrownBy(() -> service.recover(principal, workspace, request, retained))
                        .isInstanceOf(RepositoryConflictException.class);
                assertThat(fixture.remoteHead(workspace)).isEqualTo(competing);
                assertThat(candidates).hasSize(1);
                continue;
            }
            if (delivered) fixture.commitRemote(workspace, Map.of("private/later.md", bytes("later")));
            ObjectId beforeRecovery = fixture.remoteHead(workspace);
            var result = service.recover(principal, workspace, request, retained);
            assertThat(result.commit()).isEqualTo(retained.commit());
            assertThat(candidates).hasSize(delivered ? 1 : 2);
            if (delivered) assertThat(fixture.remoteHead(workspace)).isEqualTo(beforeRecovery);
            else assertThat(candidates).containsExactly(candidates.getFirst(), candidates.getFirst());
            var reader = new JGitRepositoryContentReader(fixture.authority());
            assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/backlink.md")
                            .source())
                    .contains("[note](renamed/note.md)");
            assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/folder/note.md")
                            .expectedAbsence())
                    .isTrue();
            assertThat(reader.getFile(workspace, Optional.of(result.commit()), "private/renamed/note.md")
                            .source())
                    .contains("# Note");
            service.recover(principal, workspace, request, retained);
            assertThat(candidates).hasSize(delivered ? 1 : 2);
        }
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
