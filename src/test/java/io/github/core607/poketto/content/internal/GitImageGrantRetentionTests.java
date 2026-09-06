package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class GitImageGrantRetentionTests {
    private static final String BODY = "# Article\n![image](image.png)";

    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final WorkspaceId other = WorkspaceId.random();
    private final MutableClock clock = new MutableClock();
    private final AuthPrincipal actor = actor();

    @BeforeEach
    void canonicalStorageRoot() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    void issuedGrantSurvivesEvictionAttemptAndDerivedCacheDeletionUntilExactExpiry() throws Exception {
        var fixture = fixture(1);
        var snapshots = snapshots(fixture);
        var service = service(fixture, snapshots, new JGitRepositoryBlobReader(fixture.authority()));
        String token = publicToken(service);
        assertCapacityOccupied(fixture);
        clearDerivedImages();
        clock.now = clock.now.plusSeconds(299);
        assertCapacityOccupied(fixture);
        assertThat(service.readPublicImage(workspace, token).bytes()).isEqualTo(png(1));
        assertThat(fixture.cache(workspace)).isDirectory();
        clock.now = clock.now.plusSeconds(1);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
        assertThatThrownBy(() -> service.readPublicImage(workspace, token)).isInstanceOf(AssetStorageException.class);
    }

    @Test
    void renewingAnExpiringGrantRetainsTheSourceUntilTheNewTokenExpires() throws Exception {
        var fixture = fixture(1);
        var service = service(fixture, snapshots(fixture), new JGitRepositoryBlobReader(fixture.authority()));
        String original = publicToken(service);
        clock.now = clock.now.plusSeconds(242);
        String renewed = publicToken(service);
        assertThat(renewed).isNotEqualTo(original);
        clock.now = clock.now.plusSeconds(301 - 242);
        assertCapacityOccupied(fixture);
        clearDerivedImages();
        assertThat(service.readPublicImage(workspace, renewed).bytes()).isEqualTo(png(1));
        assertThatThrownBy(() -> service.readPublicImage(workspace, original))
                .isInstanceOf(AssetStorageException.class);
        clock.now = clock.now.plusSeconds(542 - 301);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
        assertThatThrownBy(() -> service.readPublicImage(workspace, renewed)).isInstanceOf(AssetStorageException.class);
    }

    @Test
    void shortSnapshotBoundsBothTokenAndSourceRetention() throws Exception {
        var fixture = fixture(1);
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), clock, Duration.ofSeconds(10));
        snapshots.refresh(workspace);
        var service = service(fixture, snapshots, new JGitRepositoryBlobReader(fixture.authority()));
        String token = publicToken(service);
        clock.now = clock.now.plusSeconds(9);
        assertCapacityOccupied(fixture);
        clock.now = clock.now.plusSeconds(1);
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
        assertThatThrownBy(() -> service.readPublicImage(workspace, token)).isInstanceOf(AssetStorageException.class);
    }

    @Test
    void unbornAndForcePushedMainPreserveTheExactIssuedImage() throws Exception {
        var fixture = fixture(1);
        var snapshots = snapshots(fixture);
        var service = service(fixture, snapshots, new JGitRepositoryBlobReader(fixture.authority()));
        String original = publicToken(service);
        try (var remote = fixture.openRemote(workspace)) {
            var update = remote.updateRef(Constants.R_HEADS + "main");
            update.setForceUpdate(true);
            update.delete();
        }
        assertThat(snapshots.refresh(workspace).articles()).isEmpty();
        clearDerivedImages();
        assertThat(service.readPublicImage(workspace, original).bytes()).isEqualTo(png(1));
        fixture.commitRemote(workspace, files(2));
        snapshots.refresh(workspace);
        String replacement = publicToken(service);
        clearDerivedImages();
        assertThat(service.readPublicImage(workspace, original).bytes()).isEqualTo(png(1));
        assertThat(service.readPublicImage(workspace, replacement).bytes()).isEqualTo(png(2));
        assertCapacityOccupied(fixture);
    }

    @Test
    void sourceLostAfterADerivedCacheHitOmitsThePreviewImageWithoutSigningAToken() throws Exception {
        var fixture = fixture(1);
        var real = new JGitRepositoryBlobReader(fixture.authority());
        var blobs = mock(RepositoryBlobReader.class, delegatesTo(real));
        var service = service(fixture, snapshots(fixture), blobs);
        service.readExact(actor, workspace, new AssetSource.Repository(Optional.empty(), "image.png"));
        clearInvocations(blobs);
        doAnswer(invocation -> {
                    // Preview has already resolved the source descriptor and read its derived cache hit.
                    fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
                    real.protect(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(blobs)
                .protect(any(), any());
        var preview = service.preview(actor, workspace, "article.md", BODY, Optional.empty());
        assertThat(preview.body()).isEqualTo(BODY);
        assertThat(preview.images()).isEmpty();
        assertThat(fixture.cache(workspace)).doesNotExist();
        verify(blobs, never()).read(any());
        verify(blobs).protect(any(), any());
    }

    @Test
    void sourceProtectionDoesNotHoldTheGlobalGrantMonitor() throws Exception {
        var fixture = fixture(2);
        fixture.commitRemote(other, files(2));
        var snapshots = snapshots(fixture);
        snapshots.refresh(other);
        var real = new JGitRepositoryBlobReader(fixture.authority());
        var blobs = mock(RepositoryBlobReader.class, delegatesTo(real));
        var service = service(fixture, snapshots, blobs);
        String token = publicToken(service);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    RepositoryBlob descriptor = invocation.getArgument(0);
                    if (descriptor.workspaceId().equals(other)) {
                        entered.countDown();
                        await(release);
                    }
                    real.protect(descriptor, invocation.getArgument(1));
                    return null;
                })
                .when(blobs)
                .protect(any(), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var minting = pool.submit(() -> service.publicDocument(other, "/article"));
            try {
                await(entered);
                assertThat(pool.submit(() -> service.readPublicImage(workspace, token)
                                        .bytes())
                                .get(5, TimeUnit.SECONDS))
                        .isEqualTo(png(1));
                assertThat(minting).isNotDone();
            } finally {
                release.countDown();
            }
            assertThat(minting.get(5, TimeUnit.SECONDS).orElseThrow().media().images())
                    .hasSize(1);
        }
    }

    @Test
    void validationAndRetentionShareOneContinuousPin() throws Exception {
        var fixture = fixture(1);
        var descriptor = descriptor(fixture);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var authority = mock(RepositoryAuthority.class, delegatesTo(fixture.authority()));
        doAnswer(invocation -> {
                    RepositoryAuthority.ObjectReaderAction<Void> validation = invocation.getArgument(2);
                    fixture.authority()
                            .protectImmutableObjects(invocation.getArgument(0), invocation.getArgument(1), objects -> {
                                validation.read(objects);
                                entered.countDown();
                                await(release);
                                return null;
                            });
                    return null;
                })
                .when(authority)
                .protectImmutableObjects(any(), any(), any());
        var blobs = new JGitRepositoryBlobReader(authority);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var protecting = pool.submit(() -> blobs.protect(descriptor, clock.now.plusSeconds(30)));
            try {
                await(entered);
                assertCapacityOccupied(fixture);
            } finally {
                release.countDown();
            }
            protecting.get(5, TimeUnit.SECONDS);
            assertCapacityOccupied(fixture);
            clock.now = clock.now.plusSeconds(30);
            fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
            assertThat(fixture.cache(workspace)).doesNotExist();
        }
    }

    @Test
    void expiryDuringSourceValidationDoesNotLeaveProtectionOrAnActivePin() throws Exception {
        var fixture = fixture(1);
        var descriptor = descriptor(fixture);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Instant expiry = clock.now.plusSeconds(30);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var protecting =
                    pool.submit(() -> fixture.authority().protectImmutableObjects(workspace, expiry, objects -> {
                        assertThat(objects.has(
                                        org.eclipse.jgit.lib.ObjectId.fromString(descriptor.objectId()),
                                        Constants.OBJ_BLOB))
                                .isTrue();
                        entered.countDown();
                        await(release);
                        return null;
                    }));
            try {
                await(entered);
                clock.now = expiry;
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> protecting.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ContentRepositoryException.class);
            assertThat(lockCount(fixture)).isZero();
            fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
            assertThat(fixture.cache(workspace)).doesNotExist();
        }
    }

    @Test
    void candidateExpiryIsRecheckedAfterProtectionIncludingClockRollback() throws Exception {
        for (long shift : new long[] {-1, 300}) {
            var fixture = new RemoteRepositoryFixture(directory.resolve("clock-" + shift), 1, clock);
            fixture.commitRemote(workspace, files(1));
            var real = new JGitRepositoryBlobReader(fixture.authority());
            var blobs = mock(RepositoryBlobReader.class, delegatesTo(real));
            var service = service(fixture, snapshots(fixture), blobs);
            doAnswer(invocation -> {
                        real.protect(invocation.getArgument(0), invocation.getArgument(1));
                        clock.now = clock.now.plusSeconds(shift);
                        return null;
                    })
                    .when(blobs)
                    .protect(any(), any());
            var preview = service.preview(actor, workspace, "article.md", BODY, Optional.empty());
            assertThat(preview.body()).isEqualTo(BODY);
            assertThat(preview.images()).isEmpty();
        }
    }

    @Test
    void shorterProtectionCannotUndoALongerGrantAndExpiredEntriesAreReclaimed() throws Exception {
        var fixture = fixture(1);
        var blobs = new JGitRepositoryBlobReader(fixture.authority());
        for (int iteration = 0; iteration < 8; iteration++) {
            var descriptor = descriptor(fixture);
            blobs.protect(descriptor, clock.now.plusSeconds(120));
            blobs.protect(descriptor, clock.now.plusSeconds(30));
            clock.now = clock.now.plusSeconds(119);
            assertCapacityOccupied(fixture);
            assertThat(lockCount(fixture)).isOne();
            clock.now = clock.now.plusSeconds(1);
            fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
            assertThat(lockCount(fixture)).isZero();
        }
    }

    @Test
    void invalidDescriptorsOrExpiriesNeverRetainACache() throws Exception {
        var fixture = fixture(1);
        var blobs = new JGitRepositoryBlobReader(fixture.authority());
        var descriptor = descriptor(fixture);
        for (var forged : List.of(
                new RepositoryBlob(
                        workspace, "1".repeat(40), descriptor.path(), descriptor.objectId(), descriptor.size(), true),
                new RepositoryBlob(
                        workspace, descriptor.commit(), "absent.png", descriptor.objectId(), descriptor.size(), true),
                new RepositoryBlob(
                        workspace, descriptor.commit(), descriptor.path(), "1".repeat(40), descriptor.size(), true),
                new RepositoryBlob(
                        workspace,
                        descriptor.commit(),
                        descriptor.path(),
                        descriptor.objectId(),
                        descriptor.size() + 1,
                        true))) {
            assertThatThrownBy(() -> blobs.protect(forged, clock.now.plusSeconds(30)))
                    .isInstanceOf(ContentRepositoryException.class);
        }
        for (Instant expiry : List.of(clock.now, clock.now.minusSeconds(1), clock.now.plusSeconds(301))) {
            assertThatThrownBy(() -> blobs.protect(descriptor, expiry)).isInstanceOf(ContentRepositoryException.class);
        }
        assertThat(lockCount(fixture)).isZero();
        fixture.authority().readObjects(other, snapshot -> snapshot.commitId());
        assertThat(fixture.cache(workspace)).doesNotExist();
    }

    private RemoteRepositoryFixture fixture(int capacity) throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, capacity, clock);
        fixture.commitRemote(workspace, files(1));
        return fixture;
    }

    private RepositoryBlob descriptor(RemoteRepositoryFixture fixture) {
        String commit = fixture.authority()
                .readObjects(workspace, snapshot -> snapshot.commitId().orElseThrow());
        return new JGitRepositoryBlobReader(fixture.authority())
                .find(workspace, commit, "image.png")
                .orElseThrow();
    }

    private JGitPublicContentSnapshots snapshots(RemoteRepositoryFixture fixture) {
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), clock, Duration.ofHours(1));
        snapshots.refresh(workspace);
        return snapshots;
    }

    private AssetService service(
            RemoteRepositoryFixture fixture, JGitPublicContentSnapshots snapshots, RepositoryBlobReader blobs) {
        var auth = mock(AuthService.class);
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        return new AssetService(
                auth,
                new JGitRepositoryContentReader(fixture.authority()),
                blobs,
                new RepositoryMarkdownConfiguration().repositoryMarkdownInspector(),
                snapshots,
                () -> mock(ManagedBlobStore.class),
                directory.resolve("images"),
                16L * 1024 * 1024,
                128,
                clock,
                new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 16, Duration.ZERO));
    }

    private String publicToken(AssetService service) {
        String url = service.publicDocument(workspace, "/article")
                .orElseThrow()
                .media()
                .images()
                .get("image.png");
        assertThat(url).isNotNull();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private void assertCapacityOccupied(RemoteRepositoryFixture fixture) {
        assertThatThrownBy(() -> fixture.authority().readObjects(other, snapshot -> snapshot.commitId()))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("active or protected workspaces");
    }

    private void clearDerivedImages() throws Exception {
        Path images = directory.resolve("images");
        try (var files = Files.list(images)) {
            for (Path file : files.toList()) {
                assertThat(file.toAbsolutePath().normalize().startsWith(directory))
                        .isTrue();
                assertThat(file).isRegularFile();
                Files.delete(file);
            }
        }
    }

    private static int lockCount(RemoteRepositoryFixture fixture) {
        // The retained lifecycle table must stay bounded after caches and leases expire.
        return ((Map<?, ?>) ReflectionTestUtils.getField(fixture.authority(), "workspaceLocks")).size();
    }

    private static Map<String, byte[]> files(int color) throws Exception {
        return Map.of(
                RepositoryPublishingPolicy.PATH,
                "enabled: true\nmode: public-by-default\n".getBytes(StandardCharsets.UTF_8),
                "article.md",
                BODY.getBytes(StandardCharsets.UTF_8),
                "image.png",
                png(color));
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static AuthPrincipal actor() {
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        UUID id = UUID.randomUUID();
        when(actor.subjectId()).thenReturn(id);
        when(actor.accountId()).thenReturn(id);
        return actor;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS))
                    .as("concurrent operation reached its barrier")
                    .isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-07T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
