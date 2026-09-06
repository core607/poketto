package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicCacheConcurrencyTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final MutableClock clock = new MutableClock();
    private final FetchGate transport = new FetchGate();

    @BeforeEach
    void canonicalStorageRoot() throws Exception {
        directory = directory.toRealPath();
    }

    @Test
    void missingPublicRouteDoesNotWaitForPrivatePreviewFetch() throws Exception {
        var state = state();
        var gate = transport.arm();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preview = pool.submit(
                    () -> state.service.preview(state.actor, workspace, "article.md", "# Draft", Optional.empty()));
            try {
                await(gate.entered);
                assertThat(pool.submit(() -> state.service.publicDocument(workspace, "/"))
                                .get(2, TimeUnit.SECONDS))
                        .isEmpty();
                assertThat(preview).isNotDone();
            } finally {
                gate.release.countDown();
            }
            assertThat(preview.get(5, TimeUnit.SECONDS).body()).isEqualTo("# Draft");
        }
    }

    @Test
    void exactExistingBlobDoesNotWaitForRemoteFetch() throws Exception {
        var state = state();
        var blob = state.blobs.find(workspace, state.commit, "image.png").orElseThrow();
        var gate = transport.arm();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var refresh = pool.submit(() -> state.snapshots.refresh(workspace));
            try {
                await(gate.entered);
                assertThat(pool.submit(() -> state.blobs.read(blob)).get(2, TimeUnit.SECONDS))
                        .isEqualTo(png());
                assertThat(refresh).isNotDone();
            } finally {
                gate.release.countDown();
            }
            assertThat(refresh.get(5, TimeUnit.SECONDS).commit()).contains(state.commit);
        }
    }

    @Test
    void existingPublicPageCanPrepareProtectAndReadItsImageDuringFetch() throws Exception {
        var state = state();
        var original = state.snapshots.current(workspace);
        var gate = transport.arm();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var refresh = pool.submit(() -> state.snapshots.refresh(workspace));
            try {
                await(gate.entered);
                var page = pool.submit(() -> state.service.publicDocument(workspace, "/article"))
                        .get(2, TimeUnit.SECONDS)
                        .orElseThrow();
                assertThat(page.snapshot()).isSameAs(original);
                var image = page.media().images().get("image.png");
                assertThat(image).isNotNull();
                assertThat(pool.submit(() -> state.service
                                        .readPublicImage(workspace, token(image))
                                        .bytes())
                                .get(2, TimeUnit.SECONDS))
                        .isEqualTo(png());
                assertThat(state.memory.reservedBytes()).isZero();
                assertThat(refresh).isNotDone();
            } finally {
                gate.release.countDown();
            }
            assertThat(refresh.get(5, TimeUnit.SECONDS).commit()).contains(state.commit);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observedWithdrawalClosesBeforeMarkerIoAndNeverRestoresOldAuthorization(boolean invalid) throws Exception {
        var marker = spy(new PublicSnapshotMarker());
        var pauseClosed = new AtomicBoolean();
        var gate = new Gate();
        doAnswer(invocation -> {
                    if (pauseClosed.get() && !invocation.<Boolean>getArgument(3)) {
                        gate.entered.countDown();
                        await(gate.release);
                    }
                    return invocation.callRealMethod();
                })
                .when(marker)
                .write(any(), anyString(), any(), anyBoolean());
        var state = state(marker);
        String original = token(state.service
                .publicDocument(workspace, "/article")
                .orElseThrow()
                .media()
                .images()
                .get("image.png"));
        state.fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        text(invalid ? "enabled: [broken" : "enabled: false\nmode: public-by-default\n")));
        pauseClosed.set(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var closing = pool.submit(() -> state.snapshots.refresh(workspace));
            try {
                await(gate.entered);
                // Removing publication precedes marker I/O; serving must fail without waiting
                // for disk persistence or falling back to the earlier OPEN snapshot.
                pool.submit(() -> {
                            assertThatThrownBy(() -> state.snapshots.current(workspace))
                                    .isInstanceOf(ContentRepositoryException.class);
                            assertThatThrownBy(() -> state.service.publicDocument(workspace, "/article"))
                                    .isInstanceOf(ContentRepositoryException.class);
                        })
                        .get(2, TimeUnit.SECONDS);
                assertThat(closing).isNotDone();
            } finally {
                gate.release.countDown();
            }
            if (invalid) {
                assertThatThrownBy(() -> closing.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ContentRepositoryException.class);
                assertThatThrownBy(() -> state.service.publicDocument(workspace, "/article"))
                        .isInstanceOf(ContentRepositoryException.class);
            } else {
                assertThat(closing.get(5, TimeUnit.SECONDS).articles()).isEmpty();
                assertThat(state.service.publicDocument(workspace, "/article")).isEmpty();
            }
        }
        assertThat(state.service.readPublicImage(workspace, original).bytes()).isEqualTo(png());
        clock.now = clock.now.plusSeconds(300);
        assertThatThrownBy(() -> state.service.readPublicImage(workspace, original))
                .isInstanceOf(io.github.core607.poketto.assets.AssetStorageException.class);
    }

    @Test
    void finalPublicationGateCanProtectSourcesWhileAnInstallerOwnsTheAuthorityMutex() throws Exception {
        var state = state();
        var blob = state.blobs.find(workspace, state.commit, "image.png").orElseThrow();
        state.fixture.commitRemote(workspace, Map.of("private/closed.md", text("# Closed")));
        var gate = transport.arm();
        var publishing = new Gate();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var signing = pool.submit(() -> state.snapshots.withCurrent(workspace, snapshot -> {
                publishing.entered.countDown();
                await(publishing.release);
                return snapshot.commit();
            }));
            await(publishing.entered);
            var installing = pool.submit(() -> state.snapshots.refresh(workspace));
            try {
                await(gate.entered);
                gate.release.countDown();
                await(gate.fetched);
                assertThatThrownBy(() -> installing.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                // The installer still owns the authority mutex while it waits for publication.
                // Source protection must complete in that state to avoid a reverse lock order.
                pool.submit(() -> {
                            state.blobs.protect(blob, clock.instant().plusSeconds(300));
                            assertThat(state.blobs.read(blob)).isEqualTo(png());
                            return null;
                        })
                        .get(2, TimeUnit.SECONDS);
            } finally {
                gate.release.countDown();
                publishing.release.countDown();
            }
            assertThat(signing.get(5, TimeUnit.SECONDS)).contains(state.commit);
            assertThat(installing.get(5, TimeUnit.SECONDS).articles()).isEmpty();
        }
    }

    @Test
    void snapshotExpiryAndClockRollbackFailWithoutWaitingForFetch() throws Exception {
        var state = state();
        var original = state.snapshots.current(workspace);
        var gate = transport.arm();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var refresh = pool.submit(() -> state.snapshots.refresh(workspace));
            try {
                await(gate.entered);
                for (Instant invalidTime :
                        new Instant[] {original.verifiedAt().minusSeconds(1), original.expiresAt()}) {
                    clock.now = invalidTime;
                    pool.submit(() -> assertThatThrownBy(() -> state.service.publicDocument(workspace, "/article"))
                                    .isInstanceOf(ContentRepositoryException.class))
                            .get(2, TimeUnit.SECONDS);
                }
                assertThat(refresh).isNotDone();
            } finally {
                gate.release.countDown();
            }
            refresh.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void callbackFailureAndMissingWorkspaceDoNotChangePublicationOrCreateACache() throws Exception {
        var state = state();
        var original = state.snapshots.current(workspace);
        assertThatThrownBy(() -> state.snapshots.withCurrent(workspace, snapshot -> {
                    throw new IllegalArgumentException("callback failed");
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.snapshots.current(workspace)).isSameAs(original);
        String emptyResult = state.snapshots.withCurrent(workspace, snapshot -> null);
        assertThat(emptyResult).isNull();
        WorkspaceId missing = WorkspaceId.random();
        assertThatThrownBy(() -> state.snapshots.withCurrent(missing, snapshot -> "unexpected"))
                .isInstanceOf(ContentRepositoryException.class);
        assertThat(state.fixture.cache(missing)).doesNotExist();
    }

    private State state() throws Exception {
        return state(new PublicSnapshotMarker());
    }

    private State state(PublicSnapshotMarker marker) throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport, clock);
        String commit = fixture.commitRemote(
                        workspace,
                        Map.of(
                                RepositoryPublishingPolicy.PATH,
                                text("enabled: true\nmode: public-by-default\n"),
                                "article.md",
                                text("# Article\n![image](image.png)"),
                                "image.png",
                                png()))
                .name();
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), clock, Duration.ofHours(1), marker);
        snapshots.refresh(workspace);
        var blobs = new JGitRepositoryBlobReader(fixture.authority());
        var auth = mock(AuthService.class);
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        var memory = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 2, Duration.ZERO);
        var service = new AssetService(
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
                memory);
        return new State(fixture, snapshots, blobs, service, actor, memory, commit);
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static byte[] text(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private record State(
            RemoteRepositoryFixture fixture,
            JGitPublicContentSnapshots snapshots,
            JGitRepositoryBlobReader blobs,
            AssetService service,
            AuthPrincipal actor,
            ImageMemoryAdmission memory,
            String commit) {}

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch fetched = new CountDownLatch(1);
    }

    private static final class FetchGate implements RemoteGitTransport {
        private final RemoteGitTransport delegate = new JGitRemoteGitTransport();
        private volatile Gate next;

        Gate arm() {
            next = new Gate();
            return next;
        }

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            Gate gate = next;
            next = null;
            if (gate != null) {
                gate.entered.countDown();
                await(gate.release);
            }
            ObjectId commit = delegate.fetchMain(repository, binding);
            if (gate != null) gate.fetched.countDown();
            return commit;
        }

        @Override
        public PushStatus pushMain(
                Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
            return delegate.pushMain(repository, binding, expected, candidate);
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
