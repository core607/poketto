package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicImagePreparationTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final MutableClock clock = new MutableClock();
    private final ImageMemoryAdmission memory =
            new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 2, Duration.ZERO);
    private final AuthService auth = mock(AuthService.class);

    @BeforeEach
    void initialize() throws Exception {
        directory = directory.toRealPath();
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    }

    @Test
    void withdrawalFinishesDuringPayloadPreparationAndOnlyAnEarlierGrantSurvives() throws Exception {
        var state = state(Duration.ofHours(1));
        String existing = token(state.service
                .publicDocument(workspace, "/article")
                .orElseThrow()
                .media()
                .images()
                .get("image-1.png"));
        clearImageCache();
        clearInvocations(state.blobs);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        state.gate.before = id -> {
            entered.countDown();
            await(release);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preparing = pool.submit(() -> state.service.publicDocument(workspace, "/article"));
            try {
                await(entered);
                assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.BROWSER_BYTES);
                state.fixture.commitRemote(workspace, Map.of("private/withdrawn.md", text("# Private")));
                assertThat(pool.submit(() -> state.snapshots.refresh(workspace))
                                .get(5, TimeUnit.SECONDS)
                                .articles())
                        .isEmpty();
                assertThat(preparing).isNotDone();
            } finally {
                release.countDown();
            }
            assertThat(preparing.get(5, TimeUnit.SECONDS)).isEmpty();
        }
        verify(state.blobs, never()).protect(any(), any());
        assertThat(memory.reservedBytes()).isZero();
        clearImageCache();
        assertThat(state.service.readPublicImage(workspace, existing).bytes()).isEqualTo(png(1));
        clock.now = clock.now.plusSeconds(300);
        assertThatThrownBy(() -> state.service.readPublicImage(workspace, existing))
                .isInstanceOf(AssetStorageException.class);
    }

    @Test
    void acknowledgedWriteDoesNotWaitForPayloadAndRetryUsesOnlyTheNewCommit() throws Exception {
        var state = state(Duration.ofHours(1));
        String original = state.snapshots.current(workspace).commit().orElseThrow();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        state.gate.before = id -> {
            if (id.equals(imageId(1))) {
                entered.countDown();
                await(release);
            }
        };
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        var writes = new JGitRepositoryPatchService(
                state.fixture.authority(),
                auth,
                clock,
                state.snapshots::installAcknowledged,
                state.snapshots::closePublication);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preparing = pool.submit(() -> state.service.publicDocument(workspace, "/article"));
            String committed;
            try {
                await(entered);
                var patch = new RepositoryPatch(
                        Optional.of(original),
                        List.of(new RepositoryTextChange(
                                "article.md",
                                false,
                                Optional.of(DocumentRevision.sha256(text(body(1)))),
                                Optional.of(body(2)))));
                var result =
                        pool.submit(() -> writes.apply(actor, workspace, patch)).get(5, TimeUnit.SECONDS);
                assertThat(result.committed()).isTrue();
                committed = result.commit();
                assertThat(preparing).isNotDone();
            } finally {
                release.countDown();
            }
            var page = preparing.get(5, TimeUnit.SECONDS).orElseThrow();
            assertThat(page.snapshot().commit()).contains(committed);
            assertThat(page.media().commit()).isEqualTo(committed);
            assertThat(page.media().body()).isEqualTo(body(2));
            assertThat(page.media().images()).containsOnlyKeys("image-2.png");
            assertThat(state.service
                            .readPublicImage(
                                    workspace, token(page.media().images().get("image-2.png")))
                            .bytes())
                    .isEqualTo(png(2));
        }
        verify(state.blobs, never()).protect(argThat(blob -> blob.commit().equals(original)), any());
        assertThat(state.gate.payloads).hasValue(2);
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void sameCommitRenewalUsesTheFinalExpiryWithoutRepeatingPayloadPreparation() throws Exception {
        var state = state(Duration.ofSeconds(10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        state.gate.before = id -> {
            entered.countDown();
            await(release);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preparing = pool.submit(() -> state.service.publicDocument(workspace, "/article"));
            Instant verified;
            try {
                await(entered);
                clock.now = clock.now.plusSeconds(5);
                verified = clock.now;
                pool.submit(() -> state.snapshots.refresh(workspace)).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            var page = preparing.get(5, TimeUnit.SECONDS).orElseThrow();
            assertThat(page.snapshot().verifiedAt()).isEqualTo(verified);
            assertThat(state.gate.payloads).hasValue(1);
            String token = token(page.media().images().get("image-1.png"));
            clock.now = clock.now.plusSeconds(6);
            assertThat(state.service.readPublicImage(workspace, token).bytes()).isEqualTo(png(1));
            clock.now = clock.now.plusSeconds(4);
            assertThatThrownBy(() -> state.service.readPublicImage(workspace, token))
                    .isInstanceOf(AssetStorageException.class);
        }
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void expiryWhilePreparingFailsBeforeAnyNewGrant() throws Exception {
        assertPreparationCloses(false);
    }

    @Test
    void invalidPolicyClosesPublicationWhilePayloadIsStillBlocked() throws Exception {
        assertPreparationCloses(true);
    }

    private void assertPreparationCloses(boolean invalidPolicy) throws Exception {
        var state = state(Duration.ofSeconds(10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        state.gate.before = id -> {
            entered.countDown();
            await(release);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preparing = pool.submit(() -> state.service.publicDocument(workspace, "/article"));
            try {
                await(entered);
                if (invalidPolicy) {
                    state.fixture.commitRemote(
                            workspace, Map.of(RepositoryPublishingPolicy.PATH, text("enabled: [invalid")));
                    assertThatThrownBy(() -> pool.submit(() -> state.snapshots.refresh(workspace))
                                    .get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(ContentRepositoryException.class);
                } else clock.now = clock.now.plusSeconds(10);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> preparing.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ContentRepositoryException.class);
        }
        verify(state.blobs, never()).protect(any(), any());
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void repeatedCommitChurnStopsAfterTwoPreparationsWithoutSigningEither() throws Exception {
        var state = state(Duration.ofHours(1));
        CountDownLatch[] entered = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        var sequence = new AtomicInteger();
        state.gate.before = id -> {
            int index = sequence.getAndIncrement();
            if (index >= 2) throw new AssertionError("preparation retry was not bounded");
            entered[index].countDown();
            await(release[index]);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var preparing = pool.submit(() -> state.service.publicDocument(workspace, "/article"));
            try {
                for (int index = 0; index < 2; index++) {
                    await(entered[index]);
                    state.fixture.commitRemote(workspace, files(index + 2));
                    pool.submit(() -> state.snapshots.refresh(workspace)).get(5, TimeUnit.SECONDS);
                    release[index].countDown();
                }
                assertThatThrownBy(() -> preparing.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ContentRepositoryException.class)
                        .cause()
                        .hasMessageContaining("both image preparation attempts");
            } finally {
                for (var latch : release) latch.countDown();
            }
        }
        assertThat(sequence).hasValue(2);
        verify(state.blobs, never()).protect(any(), any());
        assertThat(memory.reservedBytes()).isZero();
    }

    private State state(Duration lifetime) throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, clock);
        fixture.commitRemote(workspace, files(1));
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), clock, lifetime);
        snapshots.refresh(workspace);
        var gate = new ReadGate(fixture.authority());
        var blobs = spy(new JGitRepositoryBlobReader(gate.authority));
        doAnswer(invocation -> {
                    assertThat(memory.reservedBytes())
                            .as("final signing retains no page image working set")
                            .isZero();
                    return invocation.callRealMethod();
                })
                .when(blobs)
                .protect(any(), any());
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
        return new State(fixture, snapshots, blobs, service, gate);
    }

    private record State(
            RemoteRepositoryFixture fixture,
            JGitPublicContentSnapshots snapshots,
            JGitRepositoryBlobReader blobs,
            AssetService service,
            ReadGate gate) {}

    private static final class ReadGate {
        final RepositoryAuthority authority;
        final AtomicInteger payloads = new AtomicInteger();
        volatile Consumer<String> before = id -> {};

        ReadGate(RepositoryAuthority real) {
            Set<String> images = Set.of(imageId(1), imageId(2), imageId(3));
            authority = mock(RepositoryAuthority.class, delegatesTo(real));
            doAnswer(invocation -> {
                        RepositoryAuthority.ObjectReaderAction<?> action = invocation.getArgument(1);
                        return real.readImmutableObjects(invocation.getArgument(0), objects -> {
                            var observed = mock(ObjectReader.class, delegatesTo(objects));
                            doAnswer(open -> {
                                        AnyObjectId object = open.getArgument(0);
                                        int type = open.getArgument(1);
                                        if (type == Constants.OBJ_BLOB && images.contains(object.name())) {
                                            payloads.incrementAndGet();
                                            before.accept(object.name());
                                        }
                                        return objects.open(object, type);
                                    })
                                    .when(observed)
                                    .open(any(AnyObjectId.class), anyInt());
                            return action.read(observed);
                        });
                    })
                    .when(authority)
                    .readImmutableObjects(any(), any());
        }
    }

    private void clearImageCache() throws Exception {
        try (var files = Files.list(directory.resolve("images"))) {
            for (var file : files.toList()) {
                assertThat(file.toAbsolutePath().normalize().startsWith(directory))
                        .isTrue();
                assertThat(file).isRegularFile();
                Files.delete(file);
            }
        }
    }

    private static Map<String, byte[]> files(int article) throws Exception {
        return Map.of(
                RepositoryPublishingPolicy.PATH,
                text("enabled: true\nmode: public-by-default\n"),
                "article.md",
                text(body(article)),
                "image-1.png",
                png(1),
                "image-2.png",
                png(2),
                "image-3.png",
                png(3));
    }

    private static String body(int image) {
        return "# Article " + image + "\n![image](image-" + image + ".png)";
    }

    private static byte[] text(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static byte[] png(int color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String imageId(int color) {
        try (var formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, png(color)).name();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
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
