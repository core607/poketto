package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryInitialization;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Initialization is one create-only change: absent template files are added at the commit that
 * was inspected, present files keep their bytes, and an empty repository gets a root commit.
 */
class ContentRepositoryInitializerTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final AuthService auth = mock(AuthService.class);

    @Test
    void expiredCreationLeasePreventsTheActualInitializationPush() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var initializer = initializer(fixture);
        assertThatThrownBy(
                        () -> initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL, () -> {
                            throw new IllegalStateException("creation lease expired");
                        }))
                .hasMessage("creation lease expired");
        assertThat(fixture.remoteHead(workspace)).isEqualTo(ObjectId.zeroId());
        var completed = initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL, () -> {});
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(completed.commit());
        assertThat(initializer
                        .apply(principal, workspace, RepositoryInitialization.Template.GENERAL, () -> {
                            throw new AssertionError("complete template must not write");
                        })
                        .commit())
                .isEqualTo(completed.commit());
    }

    @Test
    void anEmptyRepositoryReceivesTheWholeTemplateAsItsRootCommitWithPublicationDisabled() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var initializer = initializer(fixture);

        var status = initializer.status(principal, workspace, RepositoryInitialization.Template.GENERAL);
        assertThat(status.repositoryEmpty()).isTrue();
        assertThat(status.missingFiles()).isEqualTo(RepositoryInitialization.FILES);

        var outcome = initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL);
        assertThat(outcome.addedFiles()).isEqualTo(RepositoryInitialization.FILES);
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(outcome.commit());
        assertThat(parents(fixture, outcome.commit())).isZero();
        Map<String, byte[]> committed = tree(fixture, outcome.commit());
        assertThat(committed.keySet()).containsExactlyInAnyOrderElementsOf(RepositoryInitialization.FILES);
        assertThat(RepositoryPublishingPolicy.parse(committed.get(RepositoryPublishingPolicy.PATH))
                        .state())
                .isEqualTo(RepositoryPublishingPolicy.State.DISABLED);
        for (String path : RepositoryInitialization.FILES) {
            assertThat(new String(committed.get(path), StandardCharsets.UTF_8))
                    .isEqualTo(Files.readString(Path.of("content-template", path)));
        }
        verify(auth).authorize(principal, workspace, Capability.PUBLISH);

        assertThat(initializer.status(principal, workspace, RepositoryInitialization.Template.GENERAL))
                .isEqualTo(new RepositoryInitialization.Status(false, List.of()));
        var again = initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL);
        assertThat(again.addedFiles()).isEmpty();
        assertThat(again.commit()).isEqualTo(outcome.commit());
        assertThat(fixture.remoteHead(workspace).name()).isEqualTo(outcome.commit());
    }

    @Test
    void existingFilesKeepTheirBytesAndOnlyAbsentOnesAreAddedOnTopOfCurrentMain() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var files = new LinkedHashMap<String, byte[]>();
        files.put("AGENTS.md", "# My own guide\r\n".getBytes(StandardCharsets.UTF_8));
        files.put("notes/2026.md", "# Existing note".getBytes(StandardCharsets.UTF_8));
        ObjectId base = fixture.commitRemote(workspace, files);
        var initializer = initializer(fixture);

        var status = initializer.status(principal, workspace, RepositoryInitialization.Template.GENERAL);
        assertThat(status.repositoryEmpty()).isFalse();
        assertThat(status.missingFiles())
                .containsExactly("private/AGENTS.md", "public/AGENTS.md", RepositoryPublishingPolicy.PATH);

        var outcome = initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL);
        assertThat(outcome.addedFiles()).isEqualTo(status.missingFiles());
        Map<String, byte[]> committed = tree(fixture, outcome.commit());
        assertThat(committed.keySet())
                .containsExactlyInAnyOrder(
                        "AGENTS.md",
                        "notes/2026.md",
                        "private/AGENTS.md",
                        "public/AGENTS.md",
                        RepositoryPublishingPolicy.PATH);
        assertThat(committed.get("AGENTS.md")).isEqualTo(files.get("AGENTS.md"));
        assertThat(committed.get("notes/2026.md")).isEqualTo(files.get("notes/2026.md"));
        try (var remote = fixture.openRemote(workspace);
                var walk = new RevWalk(remote)) {
            assertThat(walk.parseCommit(ObjectId.fromString(outcome.commit())).getParent(0))
                    .isEqualTo(base);
        }
    }

    @Test
    void aTemplateSetAddsItsFoldersOnTopWithoutTouchingExistingFiles() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var initializer = initializer(fixture);
        var general = initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL);
        var mine = "# My journal rules\n".getBytes(StandardCharsets.UTF_8);
        var files = new LinkedHashMap<>(tree(fixture, general.commit()));
        files.put("public/journal/AGENTS.md", mine);
        fixture.commitRemote(workspace, files);

        var status = initializer.status(principal, workspace, RepositoryInitialization.Template.JOURNAL);
        assertThat(status.repositoryEmpty()).isFalse();
        assertThat(status.missingFiles()).containsExactly("private/journal/AGENTS.md");
        var outcome = initializer.apply(principal, workspace, RepositoryInitialization.Template.JOURNAL);
        assertThat(outcome.addedFiles()).containsExactly("private/journal/AGENTS.md");
        Map<String, byte[]> committed = tree(fixture, outcome.commit());
        assertThat(committed.get("public/journal/AGENTS.md")).isEqualTo(mine);
        assertThat(new String(committed.get("private/journal/AGENTS.md"), StandardCharsets.UTF_8))
                .isEqualTo(initializer
                        .template(RepositoryInitialization.Template.JOURNAL)
                        .get("private/journal/AGENTS.md"));
        assertThat(initializer
                        .apply(principal, workspace, RepositoryInitialization.Template.JOURNAL)
                        .addedFiles())
                .isEmpty();
        assertThat(RepositoryInitialization.Template.parse(null)).isEqualTo(RepositoryInitialization.Template.GENERAL);
        assertThatThrownBy(() -> RepositoryInitialization.Template.parse("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusAndApplicationRequireTheOwnerBeforeAnyRepositoryRead() {
        var reader = mock(RepositoryContentReader.class);
        var patches = mock(RepositoryPatchService.class);
        doThrow(new AuthException(AuthException.Code.DENIED))
                .when(auth)
                .authorize(principal, workspace, Capability.MANAGE_KEYS);
        var initializer = new ContentRepositoryInitializer(auth, reader, patches);

        assertThatThrownBy(() -> initializer.status(principal, workspace, RepositoryInitialization.Template.GENERAL))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> initializer.apply(principal, workspace, RepositoryInitialization.Template.GENERAL))
                .isInstanceOf(AuthException.class);
        verifyNoInteractions(reader, patches);
    }

    @Test
    void theShippedTemplateIsTheRepositoryTemplate() throws Exception {
        var initializer = new ContentRepositoryInitializer(
                auth, mock(RepositoryContentReader.class), mock(RepositoryPatchService.class));
        assertThat(RepositoryInitialization.FILES).contains(RepositoryPublishingPolicy.PATH);
        // The build packages whatever the directory holds; the code's list must be exactly that.
        Path template = Path.of("content-template");
        assertThat(files(template).stream().filter(path -> !path.startsWith("sets/")))
                .containsExactlyInAnyOrderElementsOf(RepositoryInitialization.FILES);
        for (String path : RepositoryInitialization.FILES) {
            assertThat(initializer.template().get(path)).isEqualTo(Files.readString(Path.of("content-template", path)));
        }
        // Every set directory is a known template, and each lists exactly its own files.
        try (var sets = Files.list(template.resolve("sets"))) {
            assertThat(sets.map(set -> set.getFileName().toString()).toList())
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(RepositoryInitialization.Template.values())
                            .filter(set -> !set.extras().isEmpty())
                            .map(RepositoryInitialization.Template::slug)
                            .toList());
        }
        for (var set : RepositoryInitialization.Template.values()) {
            Path root = template.resolve("sets").resolve(set.slug());
            assertThat(Files.isDirectory(root) ? files(root) : List.of())
                    .containsExactlyInAnyOrderElementsOf(set.extras());
            for (String path : set.extras()) {
                assertThat(initializer.template(set).get(path)).isEqualTo(Files.readString(root.resolve(path)));
            }
        }
    }

    private static List<String> files(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .toList();
        }
    }

    private ContentRepositoryInitializer initializer(RemoteRepositoryFixture fixture) {
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(principal.subjectId()).thenReturn(UUID.fromString("bf562fc1-f15b-4adb-80d2-b0fdab1a568d"));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var patches = new JGitRepositoryPatchService(
                fixture.authority(),
                auth,
                Clock.systemUTC(),
                (id, snapshot) -> {},
                (id, snapshot) -> {},
                mock(RepositoryMediaValidator.class));
        return new ContentRepositoryInitializer(auth, new JGitRepositoryContentReader(fixture.authority()), patches);
    }

    private Map<String, byte[]> tree(RemoteRepositoryFixture fixture, String commit) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var remote = fixture.openRemote(workspace);
                var walk = new RevWalk(remote);
                var entries = new TreeWalk(remote)) {
            entries.addTree(walk.parseCommit(ObjectId.fromString(commit)).getTree());
            entries.setRecursive(true);
            while (entries.next()) {
                files.put(
                        entries.getPathString(),
                        remote.open(entries.getObjectId(0)).getBytes());
            }
        }
        return files;
    }

    private int parents(RemoteRepositoryFixture fixture, String commit) throws Exception {
        try (var remote = fixture.openRemote(workspace);
                var walk = new RevWalk(remote)) {
            return walk.parseCommit(ObjectId.fromString(commit)).getParentCount();
        }
    }
}
