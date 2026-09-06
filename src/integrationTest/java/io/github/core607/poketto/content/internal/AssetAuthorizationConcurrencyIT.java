package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@Import(RemoteRepositoryIntegrationConfiguration.class)
class AssetAuthorizationConcurrencyIT {
    @TempDir
    static Path directory;

    private static final String INITIALIZATION = UUID.randomUUID().toString();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final AtomicInteger CASE = new AtomicInteger();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        directory = directory.toRealPath();
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            registry.add("poketto.test.repository-path", remote::toString);
        }
        registry.add("poketto.data-dir", directory::toString);
        registry.add("poketto.auth.initialization-token", () -> INITIALIZATION);
        registry.add("poketto.repository.refresh-seconds", () -> 3600);
    }

    @Autowired
    AuthService auth;

    @Autowired
    AssetService assets;

    @Autowired
    WorkspaceCatalog catalog;

    @Autowired
    PublicContentSnapshots snapshots;

    @Autowired
    ImageMemoryAdmission memory;

    @MockitoSpyBean
    RepositoryBlobReader blobs;

    private AuthPrincipal owner;
    private WorkspaceId workspace;

    @BeforeEach
    void owner() {
        try {
            auth.initializeOwner(INITIALIZATION, "asset-owner", PASSWORD);
        } catch (AuthException existing) {
            assertThat(existing.code()).isEqualTo(AuthException.Code.ALREADY_INITIALIZED);
        }
        owner = auth.authenticatePassword("asset-owner", PASSWORD);
        workspace = catalog.defaultWorkspace().id();
    }

    @Test
    void keyRevocationAndMemberSuspensionFinishBeforeAllPrivateImageReads() throws Exception {
        for (boolean key : new boolean[] {true, false}) {
            for (Operation operation : Operation.values()) assertRevocation(operation, key, false);
        }
    }

    @Test
    void revocationTakesPrecedenceOverPreparationFailureDiagnostics() throws Exception {
        assertRevocation(Operation.EXACT, true, true);
    }

    private void assertRevocation(Operation operation, boolean key, boolean failPreparation) throws Exception {
        int index = CASE.incrementAndGet();
        String folder = "private/case-" + index;
        String body = "# Private\n![image](image.png)";
        byte[] png = png(index);
        commitRemote(Map.of(folder + "/article.md", body.getBytes(StandardCharsets.UTF_8), folder + "/image.png", png));
        snapshots.refresh(workspace);
        AuthPrincipal principal;
        Runnable revoke;
        if (key) {
            var issued = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE));
            principal = auth.authenticateApiKey(issued.token());
            revoke = () -> auth.revokeApiKey(owner, workspace, issued.id());
        } else {
            var invitation = auth.createInvitation(owner, workspace);
            principal = auth.registerWithInvitation(invitation.token(), "member-" + UUID.randomUUID(), PASSWORD);
            revoke = () -> auth.changeMembership(owner, workspace, principal.accountId(), MembershipRole.MEMBER, false);
        }
        String privateToken = null;
        if (operation == Operation.TOKEN) {
            String url = assets.preview(principal, workspace, folder + "/article.md", body, Optional.empty())
                    .images()
                    .get("image.png");
            privateToken = url.substring(url.lastIndexOf('/') + 1);
        }
        Object baseline = execute(operation, principal, folder, body, privateToken);
        assertThat(baseline)
                .as("authorized baseline: %s key=%s", operation, key)
                .isNotNull();
        clearImages();
        clearInvocations(blobs);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    RepositoryBlob descriptor = invocation.getArgument(0);
                    if (descriptor.path().equals(folder + "/image.png")) {
                        entered.countDown();
                        await(release);
                        if (failPreparation)
                            throw new ContentRepositoryException(
                                    "private preparation diagnostic: " + descriptor.path());
                    }
                    return invocation.callRealMethod();
                })
                .when(blobs)
                .read(any());
        String selectedToken = privateToken;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reading = pool.submit(() -> execute(operation, principal, folder, body, selectedToken));
            try {
                await(entered);
                assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.BROWSER_BYTES);
                pool.submit(revoke).get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> auth.authorize(principal, workspace, Capability.READ_PRIVATE))
                        .isInstanceOfSatisfying(
                                AuthException.class,
                                denial -> assertThat(denial.code()).isEqualTo(AuthException.Code.DENIED));
                assertThat(reading).isNotDone();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> reading.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AuthException.class)
                    .cause()
                    .hasMessageNotContaining(folder);
        }
        verify(blobs, never()).protect(any(), any());
        assertThat(memory.reservedBytes()).isZero();
        doCallRealMethod().when(blobs).read(any());
    }

    private Object execute(Operation operation, AuthPrincipal principal, String folder, String body, String token) {
        if (operation == Operation.PREVIEW)
            return assets.preview(principal, workspace, folder + "/article.md", body, Optional.empty());
        if (operation == Operation.INVENTORY)
            return assets.repositoryImages(principal, workspace, Optional.empty(), folder + "/", 0, 100);
        // HTTP/MCP callers own the response scope through serialization. This service-entry test
        // supplies that same ownership while exercising the real final authorization boundary.
        var scope = memory.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        try (var producer = scope.producer()) {
            var image = operation == Operation.TOKEN
                    ? assets.readPrivateImage(principal, workspace, token)
                    : assets.readExact(
                            principal, workspace, new AssetSource.Repository(Optional.empty(), folder + "/image.png"));
            assertThat(image.bytes()).isNotEmpty();
            return image.revision();
        } finally {
            scope.responseComplete();
        }
    }

    private static void commitRemote(Map<String, byte[]> files) throws Exception {
        try (var repository = new FileRepositoryBuilder()
                        .setGitDir(directory.resolve("remote.git").toFile())
                        .setBare()
                        .build();
                var inserter = repository.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            var entries = cache.builder();
            for (var file :
                    files.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue()));
                entries.add(entry);
            }
            entries.finish();
            var commit = new CommitBuilder();
            commit.setTreeId(cache.writeTree(inserter));
            var previous = repository.resolve(Constants.R_HEADS + "main");
            if (previous != null) commit.setParentId(previous);
            var identity = new PersonIdent("Test Owner", "owner@invalid", Instant.now(), ZoneOffset.UTC);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("private image fixture");
            var id = inserter.insert(commit);
            inserter.flush();
            var update = repository.updateRef(Constants.R_HEADS + "main");
            update.setNewObjectId(id);
            assertThat(update.update())
                    .isIn(
                            org.eclipse.jgit.lib.RefUpdate.Result.NEW,
                            org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD);
        }
    }

    private static void clearImages() throws Exception {
        Path cache = directory.resolve("derived/repository-images");
        try (var files = Files.list(cache)) {
            for (var file : files.toList()) {
                assertThat(file.toAbsolutePath().normalize().startsWith(directory))
                        .isTrue();
                assertThat(file).isRegularFile();
                Files.delete(file);
            }
        }
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS))
                    .as("private payload barrier")
                    .isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private enum Operation {
        PREVIEW,
        INVENTORY,
        EXACT,
        TOKEN
    }
}
