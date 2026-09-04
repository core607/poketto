package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentRepositoryBootstrapTests {

    private static final WritePrincipal OWNER = new WritePrincipal(PrincipalType.ACCOUNT, "acct-test");

    @TempDir
    Path root;

    @Test
    void materializesAnEmptyPreProvisionedRemoteAsAnUnbornDisposableCache() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();

        repositories.store().ensureReady(workspace);

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
        var created = firstProcess
                .writes(new TestClock())
                .create(workspace, OWNER, new DocumentDraft("documents/note.md", "Note", List.of(), "Body"));
        assertThat(created.committed()).isTrue();
        clearReadOnly(firstProcess.cache(workspace));
        org.springframework.util.FileSystemUtils.deleteRecursively(firstProcess.cache(workspace));

        RemoteRepositoryFixture secondProcess = new RemoteRepositoryFixture(root);

        assertThat(secondProcess.store().scan(workspace))
                .singleElement()
                .satisfies(document -> assertThat(document.content().body()).isEqualTo("Body"));
    }

    @Test
    void observesDirectOwnerPushesAndDiscardsLocalCacheEdits() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        repositories.store().ensureReady(workspace);
        Path localOnly = repositories.cache(workspace).resolve("local-only.txt");
        Files.writeString(localOnly, "not authority");
        repositories.commitRemote(
                workspace,
                Map.of("documents/note.md", document("550e8400-e29b-41d4-a716-446655440000", "Owner", "Remote")));

        assertThat(repositories.store().scan(workspace))
                .singleElement()
                .satisfies(document -> assertThat(document.content().body()).isEqualTo("Remote"));
        assertThat(localOnly).doesNotExist();
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
                2);
        ContentRepositoryStore store =
                new JGitContentRepositoryStore(authority, new CanonicalDocumentCodec(), java.time.Clock.systemUTC());

        assertThatThrownBy(() -> store.ensureReady(workspace))
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
                2);

        assertThatThrownBy(() -> authority.ensureReady(workspace))
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

        repositories.store().ensureReady(workspace);

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
        new java.util.Random(607).nextBytes(image);
        byte[] note = document("550e8400-e29b-41d4-a716-446655440000", "Nested", "Body");
        repositories.commitRemote(
                workspace,
                Map.of(
                        "documents/nested/note.md", note,
                        "images/nested/image.png", image));
        repositories.store().scan(workspace);
        long coldObjectBytes = directoryBytes(repositories.cache(workspace).resolve(".git/objects"));

        repositories.commitRemote(
                workspace,
                Map.of(
                        "documents/nested/note.md",
                        note,
                        "documents/second.md",
                        document("650e8400-e29b-41d4-a716-446655440111", "Second", "Tiny"),
                        "images/nested/image.png",
                        image));
        repositories.store().scan(workspace);

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

        repositories.store().ensureReady(first);
        repositories.store().ensureReady(second);

        assertThat(repositories.cache(first)).doesNotExist();
        assertThat(repositories.cache(second)).isDirectory();
        assertThat(foreign).isDirectory();
    }

    @Test
    void evictsTheLeastRecentlyUsedIdleWorkspaceAtTheConfiguredBound() {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root, 1);
        WorkspaceId first = WorkspaceId.random();
        WorkspaceId second = WorkspaceId.random();

        repositories.store().ensureReady(first);
        assertThat(repositories.cache(first)).isDirectory();
        repositories.store().ensureReady(second);

        assertThat(repositories.cache(first)).doesNotExist();
        assertThat(repositories.cache(second)).isDirectory();
    }

    @Test
    void recordsColdAndWarmTransferCharacteristicsForNestedTextAndImageContent() throws Exception {
        RemoteRepositoryFixture repositories = new RemoteRepositoryFixture(root);
        WorkspaceId workspace = WorkspaceId.random();
        byte[] image = new byte[256 * 1024];
        new java.util.Random(607).nextBytes(image);
        repositories.commitRemote(
                workspace,
                Map.of(
                        "documents/nested/note.md",
                        document("550e8400-e29b-41d4-a716-446655440000", "Nested", "Body"),
                        "images/nested/image.png",
                        image));
        Runtime runtime = Runtime.getRuntime();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        long coldStarted = System.nanoTime();

        repositories.store().scan(workspace);

        long coldMillis = (System.nanoTime() - coldStarted) / 1_000_000;
        long coldObjectBytes = directoryBytes(repositories.cache(workspace).resolve(".git/objects"));
        long cacheBytes = directoryBytes(repositories.cache(workspace));
        long memoryAfterCold = runtime.totalMemory() - runtime.freeMemory();
        long warmStarted = System.nanoTime();

        repositories.store().scan(workspace);

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

    private static byte[] document(String id, String title, String body) {
        return ("""
                ---
                id: %s
                title: %s
                visibility: private
                tags: []
                created_at: 2026-09-01T09:00:00Z
                updated_at: 2026-09-01T09:00:00Z
                ---

                %s
                """).formatted(id, title, body).getBytes(StandardCharsets.UTF_8);
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
