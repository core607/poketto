package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NativeRepositoryExecutorTests extends NativeRepositoryTestFixture {
    @Test
    void nativeApplicationPathJournalsBeforeCommandAndRetainsTheReadyRuntime() {
        try (var executor = executor((run, timeout, handler) -> {
            AccountCopyRecord running = journal.record(owner).orElseThrow();
            assertThat(running.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            assertThat(running.executionId().toString()).isEqualTo(run.id());
        })) {
            RepositoryExecutor.ExecutionResult result = execute(executor, "new");
            AccountCopyRecord retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(retained.writer().storage().volumeId().toString()).isEqualTo(result.copyId());
            assertThat(retained.original()).isNotNull();
            assertThat(commands).anyMatch(source -> source.startsWith("git clone"));
            assertThat(commands.getLast()).isEqualTo("printf user-command");
            assertThat(sessions.values()).allMatch(session -> session.state().equals("ready"));
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void reconnectAttachesTheSameVolumeWithoutReexportingOrReplacingItsOriginals() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            assertThat(execute(executor, copy).freshSandbox()).isFalse();
            AccountCopyRecord after = journal.record(owner).orElseThrow();
            assertThat(after.writer().storage()).isEqualTo(before.writer().storage());
            assertThat(after.original()).isEqualTo(before.original());
            assertThat(after.state()).isEqualTo(before.state());
            verify(exports).create(any(), any(), any());
            verify(client).createVolume(any());
            verify(client).open(any());
            assertThat(commands).hasSize(4);
        }
    }

    @Test
    void lostObservationRetainsInterruptedCommandAndNeverReexecutesIt() {
        UUID interrupted;
        String copy;
        try (var executor = executor((run, timeout, handler) -> {
            throw new WorkerUnavailableException();
        })) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            AccountCopyRecord retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            interrupted = retained.executionId();
            copy = retained.copyId().toString();
        }
        sessions.clear();
        try (var resumed = executor((run, timeout, handler) -> {})) {
            assertThat(execute(resumed, copy).retention().lastInterruptedCommand())
                    .isEqualTo(interrupted);
            assertThat(commands.stream().filter("printf user-command"::equals)).hasSize(2);
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void unconfirmedContainmentRetainsCapacityAndReconciliationReleasesIt() {
        closeFails.set(true);
        try (var executor = executor((run, timeout, handler) -> {
            if (closeFails.get()) {
                throw new WorkerUnavailableException();
            }
        })) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            String copy = journal.record(owner).orElseThrow().copyId().toString();
            assertThatThrownBy(() -> execute(executor, copy)).isInstanceOf(ExecutionAdmissionException.class);
            closeFails.set(false);
            assertThat(execute(executor, copy).stdout()).isEqualTo("output");
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void legacyCopiesRemainUntouchedUntilAnExplicitImportPublishesTheirNativeBinding() {
        AccountCopyRecord before = legacyCopy();
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, before.copyId().toString()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).contains(before);
            verify(client, never()).createVolume(any());
            verify(client, never()).open(any());
        }
    }

    @Test
    void verifiedImportSwitchesOnlyTheWriterAndRetainsSourceAndEverySaveBaseline() throws Exception {
        AccountCopyRecord before = legacyCopy();
        Path archive = Files.write(root.resolve("kept-source.tar"), new byte[1024]);
        var migration = new NativeRepositoryMigration(client, workspaces());
        var plan = NativeRepositoryMigration.plan(
                before,
                client.info(),
                new NativeRepositoryMigration.Archive(1024, "f".repeat(64)),
                "repository",
                8_388_608,
                256);
        var fences = new AtomicInteger();
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            migration.importCopy(principal, held, plan, archive, () -> {
                assertThat(held.record()).isEqualTo(before);
                fences.incrementAndGet();
            });
            AccountCopyRecord after = held.record();
            assertThat(after.copyId()).isEqualTo(before.copyId());
            assertThat(after.owner()).isEqualTo(before.owner());
            assertThat(after.original()).isEqualTo(before.original());
            assertThat(after.state()).isEqualTo(before.state());
            assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
            assertThat(after.writer()).isEqualTo(plan.writer());
            migration.importCopy(principal, held, plan, archive, () -> {
                throw new AssertionError("already imported");
            });
        }
        assertThat(Files.readAllBytes(archive)).hasSize(1024);
        assertThat(fences.get()).isEqualTo(2);
        verify(client).send(any(), any(Path.class));
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void lostImportReplyReconcilesTheSameReceiptWithoutReplacingEitherCopy() throws Exception {
        AccountCopyRecord before = legacyCopy();
        Path archive = Files.write(root.resolve("kept-source.tar"), new byte[1024]);
        var migration = new NativeRepositoryMigration(client, workspaces());
        var plan = NativeRepositoryMigration.plan(
                before,
                client.info(),
                new NativeRepositoryMigration.Archive(1024, "f".repeat(64)),
                "repository",
                8_388_608,
                256);
        when(client.send(any(), any(Path.class))).thenAnswer(call -> {
            upload(call.getArgument(0));
            throw new WorkerUnavailableException();
        });
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, archive, () -> {}))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
            migration.importCopy(principal, held, plan, archive, () -> {});
            assertThat(held.record().writer()).isEqualTo(plan.writer());
            assertThat(held.record().state()).isEqualTo(before.state());
        }
        verify(client).send(any(), any(Path.class));
        assertThat(Files.readAllBytes(archive)).hasSize(1024);
    }

    @Test
    void unverifiedLegacyContainmentCannotStartAnImportOrChangeItsJournal() throws Exception {
        AccountCopyRecord before = legacyCopy();
        var migration = new NativeRepositoryMigration(client, workspaces());
        var plan = NativeRepositoryMigration.plan(
                before,
                client.info(),
                new NativeRepositoryMigration.Archive(1024, "f".repeat(64)),
                "repository",
                8_388_608,
                256);
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> migration.importCopy(principal, held, plan, root.resolve("unread.tar"), () -> {
                        throw new WorkerUnavailableException();
                    }))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
        }
        verify(client, never()).createVolume(any());
        verify(client, never()).open(any());
    }

    @Test
    void revokedGrantCannotOpenOrDeleteItsWorkingCopy() {
        when(auth.authorize(any(), any(), any(Capability[].class)))
                .thenThrow(new AuthException(AuthException.Code.DENIED));
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(AuthException.class);
            verify(client, never()).open(any());
            verify(client, never()).createVolume(any());
        }
    }

    @Test
    void namedDiscardCannotRemoveTheAccountsDifferentDefaultCopy() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            execute(executor, "new");
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            var result = executor.discard(
                    principal,
                    workspace,
                    new RepositoryExecutor.DiscardRequest(UUID.randomUUID().toString()),
                    cancellation);
            assertThat(result.status()).isEqualTo(RepositoryExecutor.DiscardStatus.ABSENT);
            assertThat(journal.record(owner)).contains(before);
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void aRestrictedGrantCannotAttachTheAccountsExistingPrivateCopy() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String copy = execute(executor, "new").copyId();
            AccountCopyRecord before = journal.record(owner).orElseThrow();
            when(auth.authorize(any(), any(), any(Capability[].class)))
                    .thenReturn(new WorkspaceAccess(
                            workspace, principal, MembershipRole.MEMBER, Set.of(Capability.EXECUTE_REPOSITORY)));
            assertThatThrownBy(() -> execute(executor, copy)).isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).contains(before);
            verify(client).open(any());
        }
    }

    @Test
    void cancellationBeforeAdmissionCannotCreateASandboxOrARepositoryExport() {
        when(cancellation.isCancelled()).thenReturn(true);
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            verify(client, never()).open(any());
            verify(exports, never()).create(any(), any(), any());
            assertThat(journal.record(owner)).isEmpty();
        }
    }

    @Test
    void nativeStorageBindingRoundTripsWithoutErasingLegacyJournals() {
        var legacy = new AccountCopyRecord.Writer(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String encoded = new String(SandcrabProtocol.write(legacy), StandardCharsets.UTF_8);
        String prior = encoded.replace(",\"storage\":null", "");
        assertThat(SandcrabProtocol.read(prior.getBytes(StandardCharsets.UTF_8), AccountCopyRecord.Writer.class))
                .isEqualTo(legacy);
        try (var executor = executor((run, timeout, handler) -> {})) {
            execute(executor, "new");
            AccountCopyRecord.Writer nativeWriter =
                    journal.record(owner).orElseThrow().writer();
            assertThat(SandcrabProtocol.read(SandcrabProtocol.write(nativeWriter), AccountCopyRecord.Writer.class))
                    .isEqualTo(nativeWriter);
        }
    }

    @Test
    void longOutputIsPagedThroughTheActualExecutorWithoutOpeningAnotherSandbox() {
        stdout = "猫".repeat(9000);
        try (var executor = executor((run, timeout, handler) -> {})) {
            var result = execute(executor, "new");
            String id = result.artifacts().get("stdout").artifactId();
            var page = executor.readArtifact(principal, workspace, id, 0, 65536, cancellation)
                    .orElseThrow();
            assertThat(page.bytes()).isEqualTo(stdout.getBytes(StandardCharsets.UTF_8));
            assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(16384);
            verify(client).open(any());
        }
    }

    @Test
    void readingAnEarlierArtifactDoesNotAcknowledgeAnInterruptedCommand() {
        stdout = "x".repeat(20000);
        try (var executor = executor((run, timeout, handler) -> {})) {
            var result = execute(executor, "new");
            String id = result.artifacts().get("stdout").artifactId();
            UUID interrupted = UUID.randomUUID();
            try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
                held.begin(interrupted);
            }
            assertThat(executor.readArtifact(principal, workspace, id, 0, 16, cancellation))
                    .isPresent();
            assertThat(journal.record(owner).orElseThrow().phase()).isEqualTo(AccountCopyRecord.Phase.RUNNING);
            assertThat(journal.record(owner).orElseThrow().executionId()).isEqualTo(interrupted);
        }
    }
}
