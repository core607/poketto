package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real retained files and tar archives with a native transfer stub, not an isolation acceptance test. */
@EnabledOnOs(OS.LINUX)
class NativeRepositoryMigrationLinuxTests extends NativeRepositoryTestFixture {
    private static final String PACK = """
            import pathlib,sys,tarfile
            source=pathlib.Path(sys.argv[1])
            with tarfile.open(sys.argv[2],'w',format=tarfile.USTAR_FORMAT) as archive:
                for child in sorted(source.iterdir()):
                    archive.add(child,arcname=child.name)
            """;
    private static final String UNPACK = """
            import sys,tarfile
            with tarfile.open(sys.argv[1]) as archive:
                archive.extractall(sys.argv[2],filter='data')
            """;
    private Path source;
    private Path destination;
    private Path workArchive;
    private Path homeArchive;
    private NativeRepositoryMigration migration;
    private NativeRepositoryMigration.Plan plan;
    private AccountCopyRecord before;

    @BeforeEach
    void archives() throws Exception {
        source = Files.createDirectory(root.resolve("legacy"));
        destination = Files.createDirectory(root.resolve("volume"));
        Path work = Files.createDirectories(source.resolve("work/repository/.git"));
        Files.writeString(work.resolve("HEAD"), "ref: refs/heads/local-checkpoint\n");
        Files.writeString(source.resolve("work/repository/unsaved.md"), "unsaved repository content");
        Files.write(source.resolve("work/extra.bin"), new byte[] {0, 1, -1, 42});
        Files.writeString(source.resolve("work/shared-name"), "work owns this name");
        Path home = Files.createDirectory(source.resolve("home"));
        Files.writeString(home.resolve("shared-name"), "home owns this name");
        Files.writeString(home.resolve(".settings"), "persistent private home settings");
        Files.setPosixFilePermissions(home.resolve(".settings"), PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(home.resolve("settings-link"), Path.of(".settings"));
        Files.writeString(source.resolve(".copy.json"), "host-owned metadata must stay outside both archives");
        workArchive = root.resolve("work.tar");
        homeArchive = root.resolve("home.tar");
        python(PACK, source.resolve("work"), workArchive);
        python(PACK, home, homeArchive);
        before = legacyCopy();
        migration = new NativeRepositoryMigration(client, workspaces());
        plan = NativeRepositoryMigration.plan(
                before, client.info(), archive(workArchive), archive(homeArchive), "repository", 8_388_608, 256);
        doAnswer(call -> null).when(client).register(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"work", "home"})
    void lostReplyAtEitherRootResumesItsReceiptAndPreservesTheWholeCopy(String lostRoot) throws Exception {
        var loseReply = new AtomicBoolean(true);
        transfers(lostRoot, loseReply);
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, workArchive, homeArchive, () -> {}))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
            migration.importCopy(principal, held, plan, workArchive, homeArchive, () -> {});
            assertThat(held.record().writer()).isEqualTo(plan.writer());
            assertThat(held.record().state()).isEqualTo(before.state());
            assertThat(held.record().original()).isEqualTo(before.original());
        }
        verify(client, times(2)).send(any(), any(Path.class));
        verify(client, times(2)).open(any());
        assertThat(sessions.values()).hasSize(2).allMatch(SandcrabProtocol.Session::terminal);
        assertRetainedFiles();
        assertThat(archive(workArchive))
                .isEqualTo(new NativeRepositoryMigration.Archive(
                        plan.work().upload().bytes(), plan.work().upload().sha256()));
        assertThat(archive(homeArchive))
                .isEqualTo(new NativeRepositoryMigration.Archive(
                        plan.home().upload().bytes(), plan.home().upload().sha256()));
    }

    @Test
    void failedContainmentAfterWorkLeavesHomeAndTheJournalUnpublished() throws Exception {
        transfers("none", new AtomicBoolean(false));
        closeFails.set(true);
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, workArchive, homeArchive, () -> {}))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
            assertThat(destination.resolve("home")).doesNotExist();
            verify(client, never()).forget(any(SandcrabProtocol.Upload.class));
            closeFails.set(false);
            migration.importCopy(principal, held, plan, workArchive, homeArchive, () -> {});
            assertThat(held.record().writer()).isEqualTo(plan.writer());
        }
        verify(client, times(2)).send(any(), any(Path.class));
        assertRetainedFiles();
    }

    @Test
    void finalFenceFailureRetainsBothReceiptsUntilTheSamePlanCanPublish() throws Exception {
        transfers("none", new AtomicBoolean(false));
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, workArchive, homeArchive, () -> {
                        if (uploads.size() == 2) {
                            throw new WorkerUnavailableException();
                        }
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
            verify(client, never()).forget(any(SandcrabProtocol.Upload.class));
            migration.importCopy(principal, held, plan, workArchive, homeArchive, () -> {});
            assertThat(held.record().writer()).isEqualTo(plan.writer());
        }
        verify(client, times(2)).send(any(), any(Path.class));
        assertRetainedFiles();
    }

    private void transfers(String lostRoot, AtomicBoolean loseReply) {
        doAnswer(call -> {
                    SandcrabProtocol.UploadPlan uploadPlan = call.getArgument(0);
                    Path archive = call.getArgument(1);
                    assertThat(sessions.get(uploadPlan.sessionId()).state()).isEqualTo("ready");
                    assertThat(archive(archive))
                            .isEqualTo(new NativeRepositoryMigration.Archive(uploadPlan.bytes(), uploadPlan.sha256()));
                    Path target = Files.createDirectory(destination.resolve(uploadPlan.path()));
                    python(UNPACK, archive, target);
                    SandcrabProtocol.Upload result = upload(uploadPlan);
                    if (lostRoot.equals(uploadPlan.path()) && loseReply.compareAndSet(true, false)) {
                        throw new WorkerUnavailableException();
                    }
                    return result;
                })
                .when(client)
                .send(any(), any(Path.class));
    }

    private void assertRetainedFiles() throws Exception {
        for (String name : List.of(
                "work/repository/.git/HEAD",
                "work/repository/unsaved.md",
                "work/extra.bin",
                "work/shared-name",
                "home/shared-name",
                "home/.settings")) {
            assertThat(Files.readAllBytes(destination.resolve(name)))
                    .isEqualTo(Files.readAllBytes(source.resolve(name)));
        }
        assertThat(Files.readSymbolicLink(destination.resolve("home/settings-link")))
                .isEqualTo(Path.of(".settings"));
        assertThat(Files.isExecutable(destination.resolve("home/.settings"))).isTrue();
        assertThat(destination.resolve(".copy.json")).doesNotExist();
        assertThat(source.resolve(".copy.json")).exists();
        assertThat(destination.resolve("repository")).doesNotExist();
    }

    private static NativeRepositoryMigration.Archive archive(Path path) throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return new NativeRepositoryMigration.Archive(Files.size(path), digest);
    }

    private static void python(String script, Path first, Path second) throws Exception {
        Process process = new ProcessBuilder("python3", "-I", "-c", script, first.toString(), second.toString())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(completed).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
    }
}
