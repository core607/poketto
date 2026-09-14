package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@EnabledOnOs(OS.LINUX)
class RetainedBaselineFilesTests {
    @TempDir
    Path root;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AccountCopyRecord.Owner owner =
            new AccountCopyRecord.Owner(UUID.randomUUID(), workspace.value(), true);
    private final RetainedBaseline.Identity identity =
            new RetainedBaseline.Identity(owner, UUID.randomUUID(), "a".repeat(40));
    private static final RetainedBaseline.Limits LIMITS = new RetainedBaseline.Limits(1024 * 1024, 1024 * 1024, 100);

    @Test
    void streamsRealAuthoritativeFilesThenReadsThemWithoutTheRepository() throws Exception {
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        when(auth.authorize(any(), any()))
                .thenReturn(
                        new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var captured = new ArrayList<RepositoryFile>();
        Path archive = root.resolve("original.pending");
        RetainedBaseline.Reference reference;
        try (var fixture = new PublicExecutionNativeFixture(
                root.resolve("repository"), root.resolve("exports"), auth, workspace)) {
            var selected = new RetainedBaseline.Identity(owner, identity.copyId(), fixture.sourceCommit());
            reference = RetainedBaselineFiles.write(
                    archive,
                    selected,
                    LIMITS,
                    sink -> fixture.reader(auth)
                            .visitBaseline(
                                    actor,
                                    workspace,
                                    fixture.sourceCommit(),
                                    new RepositoryBaselineLimits(100, 1024 * 1024, Duration.ofSeconds(30)),
                                    file -> {
                                        captured.add(file);
                                        sink.accept(file);
                                    }));
        }
        assertThat(reference.entries()).isEqualTo(captured.size());
        Files.move(root.resolve("repository"), root.resolve("repository-unavailable"));
        assertThat(Files.getPosixFilePermissions(archive)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(RetainedBaselineFiles.identity(archive, LIMITS.archiveBytes()))
                .isEqualTo(reference.identity());
        try (var stored = RetainedBaselineFiles.open(archive, reference, LIMITS)) {
            for (RepositoryFile file : captured.reversed()) {
                assertThat(stored.find(file.path())).contains(file);
            }
            assertThat(stored.find("missing.md")).isEmpty();
        }
    }

    @Test
    void preservesNontextPresenceDiagnosticsAndExactUnicodeWithoutInventingAbsence() throws Exception {
        var text = text("目录/猫.md", "# 猫咪\r\n原文😸");
        var directory = unreadable("目录", "NOT_REGULAR_FILE", Optional.empty());
        var binary = unreadable("raw.bin", "INVALID_UTF8", Optional.of(DocumentRevision.sha256(new byte[] {0, 1})));
        var media = unreadable("image.png", "MANAGED_MEDIA", Optional.empty());
        List<RepositoryFile> files = List.of(text, directory, binary, media);
        Path path = root.resolve("types.pending");
        var reference = RetainedBaselineFiles.write(path, identity, LIMITS, sink -> files.forEach(sink));
        try (var stored = RetainedBaselineFiles.open(path, reference, LIMITS)) {
            for (RepositoryFile file : files) {
                assertThat(stored.find(file.path())).contains(file);
            }
            assertThat(stored.find("missing.md")).isEmpty();
        }
        var closed = RetainedBaselineFiles.open(path, reference, LIMITS);
        closed.close();
        assertThatThrownBy(() -> closed.find("missing.md"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void accountsForTheHeaderAndIndexAtTheExactArchiveLimit() throws Exception {
        byte[] random = new byte[32768];
        new Random(17).nextBytes(random);
        var file = text("large.txt", Base64.getEncoder().encodeToString(random));
        var reference = RetainedBaselineFiles.write(
                root.resolve("measure.pending"), identity, LIMITS, sink -> sink.accept(file));
        assertThat(reference.bytes()).isGreaterThan(4096);
        var exact = new RetainedBaseline.Limits(reference.bytes(), LIMITS.expandedBytes(), 1);
        var copy =
                RetainedBaselineFiles.write(root.resolve("exact.pending"), identity, exact, sink -> sink.accept(file));
        assertThat(copy).isEqualTo(reference);
        var shortLimit = new RetainedBaseline.Limits(reference.bytes() - 1, LIMITS.expandedBytes(), 1);
        assertThatThrownBy(() -> RetainedBaselineFiles.write(
                        root.resolve("short.pending"), identity, shortLimit, sink -> sink.accept(file)))
                .isInstanceOf(RetainedBaselineIo.Limit.class);
    }

    @Test
    void boundsExpandedDataAndRefusesCompletionAfterAConsumedSinkFailure() throws Exception {
        var file = text("one.md", "a".repeat(2000));
        var other = text("two.md", "b".repeat(2000));
        var small = new RetainedBaseline.Limits(4096, 4096, 100);
        assertThatThrownBy(
                        () -> RetainedBaselineFiles.write(root.resolve("expanded.pending"), identity, small, sink -> {
                            sink.accept(file);
                            sink.accept(other);
                        }))
                .isInstanceOf(IOException.class)
                .hasRootCauseInstanceOf(RetainedBaselineIo.Limit.class);
        var oneEntry = new RetainedBaseline.Limits(4096, 16384, 1);
        assertThatThrownBy(() ->
                        RetainedBaselineFiles.write(root.resolve("poisoned.pending"), identity, oneEntry, sink -> {
                            sink.accept(file);
                            try {
                                sink.accept(other);
                            } catch (UncheckedIOException expected) {
                                assertThat(expected.getCause()).isInstanceOf(RetainedBaselineIo.Limit.class);
                            }
                        }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not complete");
        var reference = RetainedBaselineFiles.write(
                root.resolve("read-limit.pending"),
                identity,
                LIMITS,
                sink -> sink.accept(text("compressible.txt", "x".repeat(10000))));
        assertThatThrownBy(() -> RetainedBaselineFiles.open(root.resolve("read-limit.pending"), reference, small))
                .isInstanceOf(RetainedBaselineIo.Limit.class);
    }

    @Test
    void interruptedOrRevokedSourceCannotProduceACompleteHeader() throws Exception {
        var denial = new AuthException(AuthException.Code.DENIED);
        Path path = root.resolve("interrupted.pending");
        assertThatThrownBy(() -> RetainedBaselineFiles.write(path, identity, LIMITS, sink -> {
                    sink.accept(text("one.md", "private"));
                    throw denial;
                }))
                .isSameAs(denial);
        assertThatThrownBy(() -> RetainedBaselineFiles.identity(path, LIMITS.archiveBytes()))
                .isInstanceOf(IOException.class);
        Path empty = root.resolve("empty.pending");
        var reference = RetainedBaselineFiles.write(empty, identity, LIMITS, sink -> {});
        assertThat(reference.entries()).isZero();
        try (var stored = RetainedBaselineFiles.open(empty, reference, LIMITS)) {
            assertThat(stored.find("new.md")).isEmpty();
        }
    }

    @Test
    void rejectsDifferentIdentityCorruptionTruncationAndDuplicatePaths() throws Exception {
        Path path = root.resolve("verified.pending");
        var reference =
                RetainedBaselineFiles.write(path, identity, LIMITS, sink -> sink.accept(text("one.md", "private")));
        var changed = new RetainedBaseline.Identity(owner, UUID.randomUUID(), identity.commit());
        var alias = new RetainedBaseline.Reference(changed, reference.sha256(), reference.bytes(), reference.entries());
        assertThatThrownBy(() -> RetainedBaselineFiles.open(path, alias, LIMITS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("identity differs");
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.position(reference.bytes() - 1);
            channel.write(ByteBuffer.wrap(new byte[] {42}));
        }
        assertThatThrownBy(() -> RetainedBaselineFiles.open(path, reference, LIMITS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum differs");
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(reference.bytes() - 1);
        }
        assertThatThrownBy(() -> RetainedBaselineFiles.open(path, reference, LIMITS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size differs");
        assertThatThrownBy(() ->
                        RetainedBaselineFiles.write(root.resolve("duplicate.pending"), identity, LIMITS, sink -> {
                            sink.accept(text("same.md", "one"));
                            sink.accept(text("same.md", "two"));
                        }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicated");
    }

    @Test
    void rejectsHeaderDamageAndOverlappingIndexFramesEvenWithARecomputedArchiveDigest() throws Exception {
        Path path = root.resolve("index.pending");
        var reference = RetainedBaselineFiles.write(path, identity, LIMITS, sink -> {
            sink.accept(text("one.md", "one"));
            sink.accept(text("two.md", "two"));
        });
        RetainedBaseline.Reference changed;
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var header = RetainedBaselineHeader.read(
                    channel, LIMITS.archiveBytes(), JsonMapper.builder().build());
            var offset = ByteBuffer.allocate(Long.BYTES);
            channel.position(header.indexOffset() + 32);
            RetainedBaselineIo.readFully(channel, offset);
            long first = offset.flip().getLong();
            offset.clear();
            channel.position(header.indexOffset() + RetainedBaselineHeader.INDEX_BYTES + 32);
            RetainedBaselineIo.readFully(channel, offset);
            long earlier = Math.min(first, offset.flip().getLong());
            channel.position(header.indexOffset() + 32);
            RetainedBaselineIo.writeFully(
                    channel, ByteBuffer.allocate(8).putLong(earlier).flip());
            channel.position(header.indexOffset() + RetainedBaselineHeader.INDEX_BYTES + 32);
            RetainedBaselineIo.writeFully(
                    channel, ByteBuffer.allocate(8).putLong(earlier).flip());
            changed = new RetainedBaseline.Reference(
                    identity,
                    RetainedBaselineIo.digest(channel, reference.bytes()),
                    reference.bytes(),
                    reference.entries());
        }
        assertThatThrownBy(() -> RetainedBaselineFiles.open(path, changed, LIMITS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("overlap");
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.position(24);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertThatThrownBy(() -> RetainedBaselineFiles.identity(path, LIMITS.archiveBytes()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("header checksum");
    }

    @Test
    void rejectsAbsentRowsWrongSourcesAndMalformedQueryAliases() throws Exception {
        var absent = new RepositoryFile(
                workspace,
                Optional.of(identity.commit()),
                "missing.md",
                true,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                false);
        assertThatThrownBy(() -> RetainedBaselineFiles.write(
                        root.resolve("absent.pending"), identity, LIMITS, sink -> sink.accept(absent)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("source identity");
        var wrong = new RepositoryFile(
                workspace,
                Optional.of(identity.commit()),
                "wrong.md",
                false,
                Optional.of("one"),
                Optional.of(DocumentRevision.sha256(new byte[0])),
                List.of(),
                false);
        assertThatThrownBy(() -> RetainedBaselineFiles.write(
                        root.resolve("wrong.pending"), identity, LIMITS, sink -> sink.accept(wrong)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("revision differs");
        Path path = root.resolve("query.pending");
        var reference = RetainedBaselineFiles.write(path, identity, LIMITS, sink -> sink.accept(text("?.md", "valid")));
        try (var stored = RetainedBaselineFiles.open(path, reference, LIMITS)) {
            assertThatThrownBy(() -> stored.find("\uD800.md"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("UTF-8");
            assertThat(stored.find("?.md").orElseThrow().source()).contains("valid");
        }
    }

    private RepositoryFile text(String path, String source) {
        return new RepositoryFile(
                workspace,
                Optional.of(identity.commit()),
                path,
                false,
                Optional.of(source),
                Optional.of(DocumentRevision.sha256(source.getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
    }

    private RepositoryFile unreadable(String path, String code, Optional<DocumentRevision> revision) {
        return new RepositoryFile(
                workspace,
                Optional.of(identity.commit()),
                path,
                false,
                Optional.empty(),
                revision,
                List.of(new RepositoryDiagnostic(path, code, "unreadable original")),
                false);
    }
}
