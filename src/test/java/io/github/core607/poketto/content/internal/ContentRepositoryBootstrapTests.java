package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.FileSystemUtils;

class ContentRepositoryBootstrapTests {

    @TempDir
    Path root;

    @Test
    void anEmptyPreProvisionedRemoteBecomesAnUnbornDisposableCache() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();

        assertThat(fetch(repositories, workspace)).isEmpty();

        Path cache = repositories.cache(workspace);
        try (Repository repository = new FileRepositoryBuilder()
                .setWorkTree(cache.toFile())
                .findGitDir(cache.toFile())
                .build()) {
            assertThat(repository.resolve(Constants.R_HEADS + "main")).isNull();
            assertThat(repository.getConfig().getSubsections("remote")).isEmpty();
        }
    }

    @Test
    void deletingTheCacheAndReplacingTheAuthorityPreservesAcknowledgedContent() throws Exception {
        RemoteRepositoryFixture firstProcess = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        RepositoryPatchResult created = writer(firstProcess)
                .apply(
                        principal(),
                        workspace,
                        new RepositoryPatch(
                                Optional.empty(),
                                List.of(new RepositoryTextChange(
                                        "private/note.md", true, Optional.empty(), Optional.of("# Note\n\nBody")))));
        assertThat(created.committed()).isTrue();
        clearReadOnly(firstProcess.cache(workspace));
        FileSystemUtils.deleteRecursively(firstProcess.cache(workspace));

        RemoteRepositoryFixture secondProcess = new RemoteRepositoryFixture(root);

        assertThat(source(secondProcess, workspace, "private/note.md")).contains("# Note\n\nBody");
    }

    @Test
    void observesDirectOwnerPushesAndNeverReadsLocalCacheFiles() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        fetch(repositories, workspace);
        Path localOnly = repositories.cache(workspace).resolve("local-only.md");
        Files.writeString(localOnly, "not authority");
        repositories.commitRemote(workspace, Map.of("private/note.md", document("Owner", "Remote")));

        assertThat(source(repositories, workspace, "private/note.md")).contains("# Owner\n\nRemote\n");
        assertThat(new JGitRepositoryContentReader(repositories.authority())
                        .getFile(workspace, Optional.empty(), "local-only.md")
                        .expectedAbsence())
                .isTrue();
    }

    @Test
    void failsClosedWhenAWorkspaceHasNoRemoteBinding() {
        WorkspacePaths paths = new WorkspacePaths(root.resolve("data").toAbsolutePath());
        WorkspaceId workspace = WorkspaceId.random();
        RepositoryAuthority authority = new JGitRemoteRepositoryAuthority(
                paths,
                ignored -> {
                    throw new ContentRepositoryException("no provisioned remote binding");
                },
                new JGitRemoteGitTransport(),
                2,
                Clock.systemUTC());

        assertThatThrownBy(() -> authority.readObjects(workspace, RepositoryAuthority.Snapshot::commitId))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("no provisioned remote binding");
        assertThat(paths.contentDirectory(workspace)).doesNotExist();
    }

    @Test
    void transportFailuresDoNotExposeRemoteCoordinatesOrCredentials() throws Exception {
        String address = "https://secret-user:secret-token@127.0.0.1:1/private.git";
        RepositoryBinding binding = new RepositoryBinding(
                new URIish(address), new UsernamePasswordCredentialsProvider("secret-user", "secret-token"));
        WorkspaceId workspace = WorkspaceId.random();
        RepositoryAuthority authority = new JGitRemoteRepositoryAuthority(
                new WorkspacePaths(root.resolve("data").toAbsolutePath()),
                ignored -> binding,
                new JGitRemoteGitTransport(),
                2,
                Clock.systemUTC());

        assertThatThrownBy(() -> authority.readObjects(workspace, RepositoryAuthority.Snapshot::commitId))
                .hasMessageNotContaining(address)
                .hasMessageNotContaining("secret-user")
                .hasMessageNotContaining("secret-token")
                .hasMessageContaining("remote repository fetch failed");
        assertThat(binding.toString()).isEqualTo("RepositoryBinding[redacted]");
    }

    @Test
    void fetchedCacheMetadataDoesNotRetainTheRemoteAddress() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        Path remote = repositories.provision(workspace);
        repositories.commitRemote(workspace, Map.of("README.md", "fixture".getBytes(StandardCharsets.UTF_8)));

        fetch(repositories, workspace);

        String uri = remote.toUri().toString();
        String nativePath = remote.toAbsolutePath().toString();
        assertThat(repositories.cache(workspace).resolve(".git/FETCH_HEAD")).doesNotExist();
        try (var files = Files.walk(repositories.cache(workspace).resolve(".git"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertThat(bytes).doesNotContain(uri).doesNotContain(nativePath);
            }
        }
    }

    @Test
    void warmReadAfterARemoteAdvanceTransfersOnlyTheNewObjects() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        byte[] image = new byte[256 * 1024];
        new Random(607).nextBytes(image);
        byte[] note = document("Nested", "Body");
        repositories.commitRemote(
                workspace,
                Map.of(
                        "private/nested/note.md", note,
                        "images/nested/image.png", image));
        fetch(repositories, workspace);
        long coldObjectBytes = directoryBytes(repositories.cache(workspace).resolve(".git/objects"));

        repositories.commitRemote(
                workspace,
                Map.of(
                        "private/nested/note.md",
                        note,
                        "private/second.md",
                        document("Second", "Tiny"),
                        "images/nested/image.png",
                        image));
        fetch(repositories, workspace);

        // Fetch negotiation reports the cache's refs as haves, so an advanced remote sends the
        // new commit without resending the unchanged image or history.
        long advanceObjectBytes =
                directoryBytes(repositories.cache(workspace).resolve(".git/objects")) - coldObjectBytes;
        assertThat(advanceObjectBytes).isPositive();
        assertThat(advanceObjectBytes).isLessThan(image.length / 4);
    }

    @Test
    void ignoresForeignDirectoriesWhenBoundingTheCache() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root, 1);
        Path foreign = root.resolve("data")
                .resolve("workspaces")
                .resolve("not-a-workspace-id")
                .resolve("content");
        Files.createDirectories(foreign);
        WorkspaceId first = WorkspaceId.random();
        WorkspaceId second = WorkspaceId.random();

        fetch(repositories, first);
        fetch(repositories, second);

        assertThat(repositories.cache(first)).doesNotExist();
        assertThat(repositories.cache(second)).isDirectory();
        assertThat(foreign).isDirectory();
    }

    @Test
    void evictsTheLeastRecentlyUsedIdleWorkspaceAtTheConfiguredBound() {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root, 1);
        WorkspaceId first = WorkspaceId.random();
        WorkspaceId second = WorkspaceId.random();

        fetch(repositories, first);
        assertThat(repositories.cache(first)).isDirectory();
        fetch(repositories, second);

        assertThat(repositories.cache(first)).doesNotExist();
        assertThat(repositories.cache(second)).isDirectory();
    }

    @Test
    void recordsColdAndWarmTransferCharacteristicsForNestedTextAndImageContent() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        byte[] image = new byte[256 * 1024];
        new Random(607).nextBytes(image);
        repositories.commitRemote(
                workspace,
                Map.of("private/nested/note.md", document("Nested", "Body"), "images/nested/image.png", image));
        Runtime runtime = Runtime.getRuntime();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        long coldStarted = System.nanoTime();

        fetch(repositories, workspace);

        long coldMillis = (System.nanoTime() - coldStarted) / 1_000_000;
        long coldObjectBytes = directoryBytes(repositories.cache(workspace).resolve(".git/objects"));
        long cacheBytes = directoryBytes(repositories.cache(workspace));
        long memoryAfterCold = runtime.totalMemory() - runtime.freeMemory();
        long warmStarted = System.nanoTime();

        fetch(repositories, workspace);

        long warmMillis = (System.nanoTime() - warmStarted) / 1_000_000;
        long warmObjectBytes = directoryBytes(repositories.cache(workspace).resolve(".git/objects"));
        long memoryAfterWarm = runtime.totalMemory() - runtime.freeMemory();
        System.out.printf(
                "repository-fetch cold_ms=%d warm_ms=%d transferred_object_bytes=%d "
                        + "warm_object_bytes=%d cache_bytes=%d cold_heap_delta=%d warm_heap_delta=%d%n",
                coldMillis,
                warmMillis,
                coldObjectBytes,
                warmObjectBytes - coldObjectBytes,
                cacheBytes,
                memoryAfterCold - memoryBefore,
                memoryAfterWarm - memoryAfterCold);

        assertThat(coldObjectBytes).isPositive();
        assertThat(warmObjectBytes).isEqualTo(coldObjectBytes);
        assertThat(warmMillis).isNotNegative();
    }

    private static Optional<String> fetch(RemoteRepositoryFixture repositories, WorkspaceId workspace) {
        return repositories.authority().readObjects(workspace, RepositoryAuthority.Snapshot::commitId);
    }

    private static Optional<String> source(RemoteRepositoryFixture repositories, WorkspaceId workspace, String path) {
        return new JGitRepositoryContentReader(repositories.authority())
                .getFile(workspace, Optional.empty(), path)
                .source();
    }

    private static byte[] document(String title, String body) {
        return ("# " + title + "\n\n" + body + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static AuthPrincipal principal() {
        AuthPrincipal principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(principal.subjectId()).thenReturn(UUID.fromString("bf562fc1-f15b-4adb-80d2-b0fdab1a568d"));
        return principal;
    }

    private static JGitRepositoryPatchService writer(RemoteRepositoryFixture repositories) {
        AuthService auth = mock(AuthService.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        return new JGitRepositoryPatchService(
                repositories.authority(),
                auth,
                Clock.systemUTC(),
                (workspace, snapshot) -> {},
                (workspace, snapshot) -> {},
                mock(RepositoryMediaValidator.class));
    }

    private static void clearReadOnly(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
                if (dos != null) {
                    dos.setReadOnly(false);
                }
            }
        }
    }

    private static long directoryBytes(Path root) throws Exception {
        if (Files.notExists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            long total = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total += Files.size(path);
            }
            return total;
        }
    }
}
