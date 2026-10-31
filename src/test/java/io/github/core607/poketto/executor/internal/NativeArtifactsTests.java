package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.RepositoryExecutor.ArtifactMetadata;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeArtifactsTests {
    @TempDir
    Path temporary;

    private Path root;
    private final MutableClock clock = new MutableClock();
    private final UUID account = UUID.randomUUID();
    private final WorkspaceId workspace = WorkspaceId.random();
    private final UUID copy = UUID.randomUUID();

    @BeforeEach
    void configure() throws Exception {
        root = temporary.toRealPath().resolve("transfers");
    }

    private NativeTransferStore storage() {
        return new NativeTransferStore(root, 8 * 1024 * 1024, 64);
    }

    private AccountCopyRecord record(boolean full, UUID execution) {
        String commit = "a".repeat(40);
        var owner = new AccountCopyRecord.Owner(account, workspace.value(), full);
        var writer = new AccountCopyRecord.Writer(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var projection = full
                ? null
                : new RepositorySnapshotExports.PublicExport(
                        workspace,
                        new RepositorySnapshotExports.Export(UUID.randomUUID(), commit, "b".repeat(64), 1024),
                        "c".repeat(40),
                        "d".repeat(64),
                        Map.of(),
                        Map.of());
        return new AccountCopyRecord(
                owner,
                copy,
                1,
                clock.millis() + 604_800_000,
                writer,
                AccountCopyRecord.Phase.RUNNING,
                execution,
                null,
                new SelectedFileSaves.State(commit).snapshot(),
                projection,
                full ? new AccountCopyRecord.Original("e".repeat(64), 64, 0) : null);
    }

    private static ArtifactMetadata retain(
            NativeTransferStore storage, NativeArtifacts artifacts, AccountCopyRecord record, byte[] bytes) {
        NativeTransferStore.File file = storage.write(bytes.length, () -> {}, output -> output.write(bytes));
        return artifacts.retain(record, "result.bin", "application/octet-stream", false, file);
    }

    @Test
    void immutablePagesBelongToTheAccountCopyAndAnotherCurrentGrantCanReadThem() {
        var record = record(true, UUID.randomUUID());
        try (var storage = storage();
                var artifacts = new NativeArtifacts(storage, clock)) {
            var metadata = retain(storage, artifacts, record, new byte[] {1, 2, 3, 4, 5});
            var grant = mock(AuthPrincipal.class);
            when(grant.accountId()).thenReturn(account);
            when(grant.subjectId()).thenReturn(UUID.randomUUID());
            assertThat(artifacts.owner(grant, workspace, metadata.artifactId())).contains(record.owner());
            var checks = new AtomicInteger();
            var page = artifacts
                    .read(record, metadata.artifactId(), 1, 3, checks::incrementAndGet)
                    .orElseThrow();
            assertThat(page.bytes()).containsExactly(2, 3, 4);
            assertThat(page.size()).isEqualTo(5);
            assertThat(page.sha256()).isEqualTo(SandcrabProtocol.sha256(new byte[] {1, 2, 3, 4, 5}));
            assertThat(checks.get()).isEqualTo(2);
            when(grant.accountId()).thenReturn(UUID.randomUUID());
            assertThat(artifacts.owner(grant, workspace, metadata.artifactId())).isEmpty();
            assertThat(artifacts.read(record(false, UUID.randomUUID()), metadata.artifactId(), 0, 5, () -> {}))
                    .isEmpty();
        }
    }

    @Test
    void finalAuthorizationAndRangeValidationPrecedePageDelivery() {
        try (var storage = storage();
                var artifacts = new NativeArtifacts(storage, clock)) {
            var record = record(true, UUID.randomUUID());
            var metadata = retain(storage, artifacts, record, new byte[] {7, 8, 9});
            var checks = new AtomicInteger();
            assertThatThrownBy(() -> artifacts.read(record, metadata.artifactId(), 0, 3, () -> {
                        if (checks.incrementAndGet() == 2) {
                            throw new AuthException(AuthException.Code.DENIED);
                        }
                    }))
                    .isInstanceOf(AuthException.class);
            assertThatThrownBy(() -> artifacts.read(record, metadata.artifactId(), 4, 3, () -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(artifacts
                            .read(record, metadata.artifactId(), 3, 3, () -> {})
                            .orElseThrow()
                            .bytes())
                    .isEmpty();
        }
    }

    @Test
    void expiryDeletesBytesAndReleasesCapacityWithoutExtendingTheArtifactLifetime() throws Exception {
        try (var storage = storage();
                var artifacts = new NativeArtifacts(storage, clock)) {
            var record = record(true, UUID.randomUUID());
            var metadata = retain(storage, artifacts, record, new byte[] {1, 2});
            clock.now += 299_500;
            assertThat(artifacts
                            .read(record, metadata.artifactId(), 0, 2, () -> {})
                            .orElseThrow()
                            .expiresInSeconds())
                    .isEqualTo(1);
            clock.now += 501;
            assertThat(artifacts.read(record, metadata.artifactId(), 0, 2, () -> {}))
                    .isEmpty();
            try (var paths = Files.list(root)) {
                assertThat(paths.map(path -> path.getFileName().toString())).containsExactly(".lock");
            }
        }
    }

    @Test
    void failedCommandCleanupKeepsEarlierArtifactsWhileCopyDisposalRemovesAll() {
        try (var storage = storage();
                var artifacts = new NativeArtifacts(storage, clock)) {
            var first = record(true, UUID.randomUUID());
            var second = record(true, UUID.randomUUID());
            var kept = retain(storage, artifacts, first, new byte[] {1});
            var removed = retain(storage, artifacts, second, new byte[] {2});
            artifacts.removeExecution(copy, second.executionId());
            assertThat(artifacts.read(first, kept.artifactId(), 0, 1, () -> {})).isPresent();
            assertThat(artifacts.read(second, removed.artifactId(), 0, 1, () -> {}))
                    .isEmpty();
            artifacts.removeCopy(copy);
            assertThat(artifacts.read(first, kept.artifactId(), 0, 1, () -> {})).isEmpty();
        }
    }

    @Test
    void longOutputHasUtf8SafePreviewsAndRetrievableFullBytesWithCaptureTruncation() {
        try (var storage = storage();
                var artifacts = new NativeArtifacts(storage, clock)) {
            var record = record(true, UUID.randomUUID());
            String stdout = "猫".repeat(9000);
            String stderr = "error".repeat(4000);
            var output = artifacts.output(record, new SandcrabProtocol.Result(0, stdout, stderr, true, null), () -> {});
            assertThat(output.stdout().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(16384);
            assertThat(output.stdout()).doesNotContain("�");
            assertThat(output.artifacts()).containsOnlyKeys("stdout", "stderr");
            var metadata = output.artifacts().get("stdout");
            assertThat(metadata.truncated()).isTrue();
            assertThat(artifacts
                            .read(record, metadata.artifactId(), 0, 65536, () -> {})
                            .orElseThrow()
                            .bytes())
                    .isEqualTo(stdout.getBytes(StandardCharsets.UTF_8));
            assertThat(output.stdoutTruncated()).isTrue();
            assertThat(output.stderrTruncated()).isTrue();
        }
    }

    @Test
    void outputCapacityFailureKeepsPreviewsAndReportsTheMissingArtifact() {
        try (var storage = new NativeTransferStore(root, 32, 4);
                var artifacts = new NativeArtifacts(storage, clock)) {
            var output = artifacts.output(
                    record(true, UUID.randomUUID()),
                    new SandcrabProtocol.Result(0, "x".repeat(20000), "", false, null),
                    () -> {});
            assertThat(output.stdout()).hasSize(16384);
            assertThat(output.artifacts()).isEmpty();
            assertThat(output.errors()).containsEntry("stdout", "ARTIFACT_CAPACITY");
        }
    }

    private static final class MutableClock extends Clock {
        long now = 1_800_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
