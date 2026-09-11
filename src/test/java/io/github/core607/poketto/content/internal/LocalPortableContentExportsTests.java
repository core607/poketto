package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.ManagedOriginalTransfers;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LocalPortableContentExportsTests {
    @TempDir
    Path root;

    final WorkspaceId workspace = WorkspaceId.random();
    final AuthService auth = mock(AuthService.class);
    final AuthPrincipal actor = actor();
    final Optional<String> client = Optional.of("a".repeat(64));
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-10T00:00:00Z"));

    private AuthPrincipal actor() {
        var value = mock(AuthPrincipal.class);
        when(value.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(value.subjectId()).thenReturn(UUID.randomUUID());
        when(value.accountId()).thenReturn(UUID.randomUUID());
        return value;
    }

    private LocalPortableContentExports service(PortableContentPlanner planner) {
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        return new LocalPortableContentExports(
                auth,
                planner,
                root.resolve("packages"),
                clock,
                new LocalPortableContentExports.Limits(
                        1048576, 3145728, 2097152, 2, Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    private PortableContentPlanner planner(String body) throws Exception {
        var fixture = new RemoteRepositoryFixture(root.resolve("git-" + UUID.randomUUID()));
        fixture.commitRemote(workspace, Map.of("private/note.md", body.getBytes(StandardCharsets.UTF_8)));
        return new PortableContentPlanner(
                auth,
                new JGitRepositoryContentReader(fixture.authority()),
                new JGitRepositoryBlobReader(fixture.authority()),
                mock(PublicContentSnapshots.class),
                new ManagedOriginalTransfers(() -> mock(ManagedBlobStore.class)));
    }

    private PortableContentExports.Export create(LocalPortableContentExports service) {
        return service.create(actor, workspace, List.of("private/note.md"), false, client);
    }

    private Path file(PortableContentExports.Export receipt) {
        return root.resolve("packages").resolve(workspace.value().toString()).resolve(receipt.handle() + ".zip");
    }

    @Test
    void ownerScopeAndCurrentReadPermissionGuardActualZipBytes() throws Exception {
        try (var service = service(planner("# Note\nprivate bytes\n"))) {
            var receipt = create(service);
            assertThat(Files.getPosixFilePermissions(file(receipt)))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            var output = new ByteArrayOutputStream();
            service.copyTo(actor, workspace, receipt.handle(), client, output);
            assertThat(output.size()).isEqualTo(receipt.bytes());
            assertThat(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray())))
                    .isEqualTo(receipt.sha256());
            assertThatThrownBy(() -> service.describe(actor, WorkspaceId.random(), receipt.handle(), client))
                    .isInstanceOf(ContentExportException.class);
            assertThatThrownBy(() -> service.describe(actor(), workspace, receipt.handle(), client))
                    .isInstanceOf(ContentExportException.class);
            assertThatThrownBy(() -> service.describe(actor, workspace, receipt.handle(), Optional.of("b".repeat(64))))
                    .isInstanceOf(ContentExportException.class);
            assertThatThrownBy(() -> service.describe(actor, workspace, receipt.handle(), Optional.empty()))
                    .isInstanceOf(ContentExportException.class);
            doThrow(new SecurityException("revoked")).when(auth).authorize(actor, workspace, Capability.READ_PRIVATE);
            output.reset();
            assertThatThrownBy(() -> service.copyTo(actor, workspace, receipt.handle(), client, output))
                    .isInstanceOf(SecurityException.class);
            assertThat(output.size()).isZero();
        }
    }

    @Test
    void expiryReclaimsDiskAndCapacityAndRestartRemovesOnlyOwnedAbandonedPackages() throws Exception {
        var planner = planner("# Note\n");
        Path abandoned;
        try (var service = service(planner)) {
            var first = create(service);
            var second = create(service);
            assertThatThrownBy(() -> create(service))
                    .isInstanceOfSatisfying(
                            ContentExportException.class,
                            error -> assertThat(error.reason()).isEqualTo(ContentExportException.Reason.CAPACITY));
            now.set(now.get().plusSeconds(3));
            assertThatThrownBy(() -> service.describe(actor, workspace, first.handle(), client))
                    .isInstanceOf(ContentExportException.class);
            var replacement = create(service);
            assertThat(file(first)).doesNotExist();
            assertThat(file(second)).doesNotExist();
            abandoned = file(replacement).getParent().resolve(UUID.randomUUID() + ".pending");
            Files.writeString(abandoned, "incomplete");
        }
        try (var restarted = service(planner)) {
            create(restarted);
            assertThat(abandoned).doesNotExist();
        }
    }

    @Test
    void closedInstanceCannotRemoveANewOwnersEmptyWorkspaceDirectory() throws Exception {
        var service = service(planner("# Note\n"));
        create(service);
        service.close();
        Path next = root.resolve("packages").resolve(workspace.value().toString());
        Files.createDirectory(next);
        assertThatThrownBy(() -> create(service)).isInstanceOf(ContentExportException.class);
        assertThat(next).isDirectory();
    }

    @Test
    void corruptionFailsBeforeReturningBytes() throws Exception {
        try (var service = service(planner("# Note\n"))) {
            var receipt = create(service);
            byte[] bytes = Files.readAllBytes(file(receipt));
            bytes[0] ^= 1;
            Files.write(file(receipt), bytes);
            var output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> service.copyTo(actor, workspace, receipt.handle(), client, output))
                    .isInstanceOf(ContentExportException.class);
            assertThat(output.size()).isZero();
        }
    }

    @Test
    void sessionCloseStopsAnActiveTransferAndDeletesAfterItsReaderReleases() throws Exception {
        byte[] bytes = new byte[160000];
        new Random(7).nextBytes(bytes);
        try (var service = service(planner("# Note\n" + Base64.getEncoder().encodeToString(bytes)))) {
            var receipt = create(service);
            assertThat(receipt.bytes()).isGreaterThan(65536);
            var output = new ByteArrayOutputStream() {
                @Override
                public synchronized void write(byte[] data, int offset, int count) {
                    super.write(data, offset, count);
                    service.closeClient(actor, workspace, client.orElseThrow());
                    assertThat(file(receipt)).exists();
                }
            };
            assertThatThrownBy(() -> service.copyTo(actor, workspace, receipt.handle(), client, output))
                    .isInstanceOf(ContentExportException.class);
            assertThat(output.size()).isBetween(1, 65536);
            assertThat(file(receipt)).doesNotExist();
        }
    }

    @Test
    void sessionCloseDuringBuildCannotPublishAHandleAfterTheCallback() throws Exception {
        var planner = mock(PortableContentPlanner.class);
        var current = new AtomicReference<LocalPortableContentExports>();
        var entry = new PortableArchiveWriter.Entry("note.md", 1, output -> {
            current.get().closeClient(actor, workspace, client.orElseThrow());
            output.write(1);
        });
        when(planner.prepare(actor, workspace, List.of("private/note.md"), false))
                .thenReturn(new PortableContentPlanner.Plan(workspace, List.of(entry), () -> {}));
        try (var service = service(planner)) {
            current.set(service);
            assertThatThrownBy(() -> create(service)).isInstanceOf(ContentExportException.class);
            try (var files = Files.walk(root.resolve("packages"))) {
                assertThat(files.filter(path -> path.toString().endsWith(".zip")
                                || path.toString().endsWith(".pending")))
                        .isEmpty();
            }
            when(planner.prepare(actor, workspace, List.of("private/note.md"), false))
                    .thenReturn(new PortableContentPlanner.Plan(
                            workspace,
                            List.of(new PortableArchiveWriter.Entry("note.md", 1, output -> output.write(1))),
                            () -> {}));
            var replacement =
                    service.create(actor, workspace, List.of("private/note.md"), false, Optional.of("b".repeat(64)));
            assertThat(file(replacement)).exists();
        }
    }

    @Test
    void overlappingBuildersFailWithoutBlockingOrConsumingTheFirstReservation() throws Exception {
        var planner = mock(PortableContentPlanner.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(planner.prepare(actor, workspace, List.of("private/note.md"), false))
                .thenReturn(new PortableContentPlanner.Plan(
                        workspace,
                        List.of(new PortableArchiveWriter.Entry("note.md", 1, output -> {
                            entered.countDown();
                            try {
                                if (!finish.await(3, TimeUnit.SECONDS)) {
                                    throw new IOException("test timed out");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IOException(error);
                            }
                            output.write(1);
                        })),
                        () -> {}));
        try (var service = service(planner)) {
            var first = CompletableFuture.supplyAsync(() -> create(service));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> create(service))
                        .isInstanceOfSatisfying(
                                ContentExportException.class,
                                error -> assertThat(error.reason()).isEqualTo(ContentExportException.Reason.CAPACITY));
            } finally {
                finish.countDown();
            }
            assertThat(file(first.get(3, TimeUnit.SECONDS))).exists();
        }
    }
}
