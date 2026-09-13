package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@EnabledOnOs(OS.LINUX)
class RetainedBaselineStoreTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private static final RetainedCopyStore.Limits METADATA =
            new RetainedCopyStore.Limits(4, 4096, 32768, 0, Duration.ofHours(1));
    private static final RetainedBaselineStore.Limits BASELINES =
            new RetainedBaselineStore.Limits(4, new RetainedBaseline.Limits(65536, 1024 * 1024, 100), 262144, 0);

    @TempDir
    Path root;

    @Test
    void publishesAndReopensUnderTheMatchingWriterWithoutBlockingMetadataDuringTraversal() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var identity = identity(120000);
        var unrelated = record(identity(120000));
        RetainedBaseline.Reference reference;
        try (var writer = stores.records().writer(identity.owner(), identity.copyId())) {
            reference = stores.baselines().capture(writer, identity, sink -> {
                stores.records().create(unrelated);
                assertThat(stores.records().read(unrelated.owner(), unrelated.copyId()))
                        .isEqualTo(unrelated);
                sink.accept(file(identity, "原始正文😸"));
            });
            assertThat(stores.baselines().collectUnused()).isZero();
            stores.records().create(record(reference));
        }
        var reopened = stores(CLOCK, METADATA, BASELINES);
        try (var writer = reopened.records().writer(identity.owner(), identity.copyId());
                var reader = reopened.baselines().open(writer, reference)) {
            assertThat(reader.find("one.md").orElseThrow().source()).contains("原始正文😸");
            assertThat(reader.find("missing.md")).isEmpty();
            assertReason(
                    () -> reopened.baselines().capture(writer, identity, sink -> {}),
                    RetainedCopyException.Reason.STALE);
        }
        assertThat(Files.getPosixFilePermissions(root.resolve("originals")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(archive())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertNoPending();
    }

    @Test
    void wrongOrReleasedWritersCannotCaptureOrReadEvenAnAbsentPath() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var identity = identity(120000);
        var wrong = identity(120000);
        var writer = stores.records().writer(identity.owner(), identity.copyId());
        try (writer) {
            assertReason(
                    () -> stores.baselines().capture(writer, wrong, sink -> {}), RetainedCopyException.Reason.STALE);
            var reference = stores.baselines().capture(writer, identity, sink -> sink.accept(file(identity, "source")));
            stores.records().create(record(reference));
            try (var reader = stores.baselines().open(writer, reference)) {
                writer.close();
                assertReason(() -> reader.find("missing.md"), RetainedCopyException.Reason.UNAVAILABLE);
            }
        }
        assertThatThrownBy(() -> new RetainedBaselineStore(root.resolve("metadata"), stores.records(), BASELINES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainedBaselineStore(root, stores.records(), BASELINES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void collectsOrphansAndExpiredCopiesButPreservesLiveRecordsAndActiveWriters() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var expired = identity(60000);
        var live = identity(120000);
        capture(stores, expired, "old", true);
        var liveReference = capture(stores, live, "live", true);
        var orphan = identity(120000);
        try (var writer = stores.records().writer(orphan.owner(), orphan.copyId())) {
            stores.baselines().capture(writer, orphan, sink -> sink.accept(file(orphan, "unacknowledged")));
            assertThat(stores.baselines().collectUnused()).isZero();
        }
        assertThat(stores.baselines().collectUnused()).isEqualTo(1);
        var collector = stores(Clock.offset(CLOCK, Duration.ofMinutes(1)), METADATA, BASELINES);
        try (var writer = stores.records().writer(expired.owner(), expired.copyId())) {
            assertThat(collector.baselines().collectUnused()).isZero();
            writer.requireValid();
        }
        assertThat(collector.baselines().collectUnused()).isEqualTo(1);
        try (var writer = collector.records().writer(live.owner(), live.copyId());
                var reader = collector.baselines().open(writer, liveReference)) {
            assertThat(reader.find("one.md").orElseThrow().source()).contains("live");
        }
    }

    @Test
    void orphanCollectionStillWorksWhenAllOrdinaryMetadataWriterSlotsAreOccupied() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var orphan = identity(120000);
        capture(stores, orphan, "orphan", false);
        var live = identity(120000);
        stores.records().create(record(live));
        try (var writer = stores.records().writer(live.owner(), live.copyId())) {
            writer.requireValid();
        }
        var one = new RetainedCopyStore.Limits(1, 4096, 32768, 0, Duration.ofHours(1));
        var full = stores(CLOCK, one, BASELINES);
        assertReason(() -> full.records().writer(orphan.owner(), orphan.copyId()), RetainedCopyException.Reason.LIMIT);
        assertThat(full.baselines().collectUnused()).isEqualTo(1);
        assertThat(full.records().read(live.owner(), live.copyId())).isEqualTo(record(live));
    }

    @Test
    void failedSourceAndCapacityRefusalsLeavePublishedOriginalsUntouched() throws Exception {
        var files = new RetainedBaseline.Limits(8192, 65536, 100);
        var limits = new RetainedBaselineStore.Limits(4, files, 8192, 0);
        var stores = stores(CLOCK, METADATA, limits);
        byte[] bytes = new byte[4096];
        new Random(47).nextBytes(bytes);
        String source = Base64.getEncoder().encodeToString(bytes);
        var live = identity(120000);
        var reference = capture(stores, live, source, true);
        assertThat(reference.bytes()).isGreaterThan(4096);
        var another = identity(120000);
        try (var writer = stores.records().writer(another.owner(), another.copyId())) {
            assertReason(
                    () -> stores.baselines().capture(writer, another, sink -> {}), RetainedCopyException.Reason.LIMIT);
        }
        try (var writer = stores.records().writer(live.owner(), live.copyId());
                var reader = stores.baselines().open(writer, reference)) {
            assertThat(reader.find("one.md").orElseThrow().source()).contains(source);
        }
        var roomy = stores(CLOCK, METADATA, BASELINES);
        var denial = new AuthException(AuthException.Code.DENIED);
        try (var writer = roomy.records().writer(another.owner(), another.copyId())) {
            assertThatThrownBy(() -> roomy.baselines().capture(writer, another, sink -> {
                        sink.accept(file(another, "not acknowledged"));
                        throw denial;
                    }))
                    .isSameAs(denial);
        }
        assertNoPending();
        var reserved = stores(CLOCK, METADATA, new RetainedBaselineStore.Limits(4, files, 65536, Long.MAX_VALUE));
        try (var writer = reserved.records().writer(another.owner(), another.copyId())) {
            assertReason(
                    () -> reserved.baselines().capture(writer, another, sink -> {}),
                    RetainedCopyException.Reason.LIMIT);
        }
    }

    @Test
    void refusesFilePermissionChangesHardLinksAndSymlinksWithoutDeletingTheirTargets() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var identity = identity(120000);
        var reference = capture(stores, identity, "private", true);
        Path archive = archive();
        try (var writer = stores.records().writer(identity.owner(), identity.copyId())) {
            Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rw-r-----"));
            assertReason(() -> stores.baselines().open(writer, reference), RetainedCopyException.Reason.UNAVAILABLE);
            Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rw-------"));
            Path link = Files.createLink(root.resolve("hardlink"), archive);
            assertReason(() -> stores.baselines().open(writer, reference), RetainedCopyException.Reason.UNAVAILABLE);
            Files.delete(link);
            Path target = root.resolve("original-target");
            Files.move(archive, target);
            Files.createSymbolicLink(archive, target);
            assertReason(() -> stores.baselines().open(writer, reference), RetainedCopyException.Reason.UNAVAILABLE);
            assertReason(stores.baselines()::collectUnused, RetainedCopyException.Reason.UNAVAILABLE);
            assertThat(target).exists();
        }
    }

    @Test
    void otherMetadataAndOriginalReadsContinueWhileAnotherArchiveIsBeingCreated() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var live = identity(120000);
        var reference = capture(stores, live, "live", true);
        var creating = identity(120000);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var capture = CompletableFuture.runAsync(() -> {
            try (var writer = stores.records().writer(creating.owner(), creating.copyId())) {
                stores.baselines().capture(writer, creating, sink -> {
                    entered.countDown();
                    await(release);
                    sink.accept(file(creating, "new"));
                });
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var writer = stores.records().writer(live.owner(), live.copyId());
                    var reader = stores.baselines().open(writer, reference)) {
                assertThat(reader.find("one.md").orElseThrow().source()).contains("live");
            }
            assertReason(stores.baselines()::collectUnused, RetainedCopyException.Reason.BUSY);
        } finally {
            release.countDown();
            capture.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void collectionCannotRemoveAnOriginalWhileAnotherProcessOwnsItsCopyWriter() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var identity = identity(120000);
        capture(stores, identity, "orphan being admitted elsewhere", false);
        Path signal = root.resolve("writer-ready");
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        RetainedStoreProcessProbe.class.getName(),
                        "hold-writer",
                        root.resolve("metadata").toString(),
                        identity.owner().subjectId().toString(),
                        identity.owner().workspaceId().toString(),
                        identity.copyId().toString(),
                        signal.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(signal).hasContent("LOCKED");
            assertThat(stores.baselines().collectUnused()).isZero();
            assertThat(archive()).exists();
            child.getOutputStream().close();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(stores.baselines().collectUnused()).isEqualTo(1);
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void collectsAbandonedStagingButRefusesCorruptArchiveIdentity() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        Path abandoned = root.resolve("originals/.pending-" + UUID.randomUUID());
        Files.writeString(abandoned, "interrupted staging");
        Files.setPosixFilePermissions(abandoned, PosixFilePermissions.fromString("rw-------"));
        assertThat(stores.baselines().collectUnused()).isZero();
        assertThat(abandoned).doesNotExist();
        var identity = identity(120000);
        capture(stores, identity, "orphan", false);
        Path archive = archive();
        byte[] bytes = Files.readAllBytes(archive);
        bytes[24] ^= 1;
        Files.write(archive, bytes);
        assertReason(stores.baselines()::collectUnused, RetainedCopyException.Reason.UNAVAILABLE);
        assertThat(archive).exists();
        assertThat(Files.readAllBytes(archive)).containsExactly(bytes);
    }

    @Test
    void configuredCollectorRemovesExpiredOriginalsWithoutAnotherClientRequest() throws Exception {
        var stores = stores(CLOCK, METADATA, BASELINES);
        var identity = identity(60000);
        capture(stores, identity, "expired", true);
        Path archive = archive();
        var expired = stores(Clock.offset(CLOCK, Duration.ofMinutes(1)), METADATA, BASELINES);
        try (var maintenance = new ExecutorRetentionConfiguration().retainedBaselineMaintenance(expired.baselines())) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (Files.exists(archive) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(archive).doesNotExist();
        }
    }

    @Test
    void configurationRequiresSeparateRootsOnlyWhenRetentionIsEnabled() {
        var context = new ApplicationContextRunner().withUserConfiguration(ExecutorRetentionConfiguration.class);
        context.run(app -> {
            assertThat(app).doesNotHaveBean(RetainedCopyStore.class);
            assertThat(app).doesNotHaveBean(RetainedBaselineStore.class);
        });
        var enabled = context.withPropertyValues(
                "poketto.executor.enabled=true",
                "poketto.executor.retention.enabled=true",
                "poketto.executor.retention.root=" + root.resolve("metadata"));
        enabled.withPropertyValues("poketto.executor.retention.baseline-root=" + root.resolve("originals"))
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    assertThat(app).hasSingleBean(RetainedCopyStore.class);
                    assertThat(app).hasSingleBean(RetainedBaselineStore.class);
                    assertThat(app.getBeansOfType(RetainedCopyMaintenance.class))
                            .hasSize(2);
                });
        enabled.run(app -> assertThat(app).hasFailed());
    }

    private Stores stores(Clock clock, RetainedCopyStore.Limits metadata, RetainedBaselineStore.Limits baselines) {
        var records = new RetainedCopyStore(root.resolve("metadata"), metadata, clock);
        return new Stores(records, new RetainedBaselineStore(root.resolve("originals"), records, baselines));
    }

    private static RetainedBaseline.Reference capture(
            Stores stores, RetainedBaseline.Identity identity, String source, boolean publishRecord) throws Exception {
        try (var writer = stores.records().writer(identity.owner(), identity.copyId())) {
            var reference = stores.baselines().capture(writer, identity, sink -> sink.accept(file(identity, source)));
            if (publishRecord) {
                stores.records().create(record(reference));
            }
            return reference;
        }
    }

    private static RetainedBaseline.Identity identity(long lifetime) {
        return new RetainedBaseline.Identity(
                new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(),
                "a".repeat(40),
                CLOCK.millis() + lifetime);
    }

    private static RepositoryFile file(RetainedBaseline.Identity identity, String source) {
        return new RepositoryFile(
                new WorkspaceId(identity.owner().workspaceId()),
                Optional.of(identity.commit()),
                "one.md",
                false,
                Optional.of(source),
                Optional.of(DocumentRevision.sha256(source.getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
    }

    private static RetainedCopyRecord record(RetainedBaseline.Identity identity) {
        return record(RetainedBaselineTestData.reference(
                identity.owner(), identity.copyId(), identity.commit(), identity.expiresAt()));
    }

    private static RetainedCopyRecord record(RetainedBaseline.Reference reference) {
        var identity = reference.identity();
        return new RetainedCopyRecord(
                1,
                identity.owner(),
                identity.copyId(),
                0,
                1,
                "b".repeat(64),
                true,
                null,
                identity.expiresAt(),
                new RetainedCopyRecord.Writer(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3)),
                new RetainedCopyRecord.Checkpoint(
                        new UUID(0, 4), "c".repeat(64), 1, new SelectedFileSaves.State(identity.commit()).snapshot()),
                null,
                null,
                reference);
    }

    private Path archive() throws Exception {
        try (var entries = Files.newDirectoryStream(root.resolve("originals"), "*.baseline")) {
            return entries.iterator().next();
        }
    }

    private void assertNoPending() throws Exception {
        try (var files = Files.list(root.resolve("originals"))) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".pending-")))
                    .isTrue();
        }
    }

    private static void assertReason(Runnable call, RetainedCopyException.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        RetainedCopyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("archive test did not release its producer");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("archive test producer interrupted", failure);
        }
    }

    private record Stores(RetainedCopyStore records, RetainedBaselineStore baselines) {}
}
