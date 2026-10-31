package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class NativeRepositoryMigrationOperatorTests extends NativeRepositoryTestFixture {
    @Test
    void preparationFixesAPlanWithoutChangingTheJournalOrCreatingNativeResources() throws Exception {
        AccountCopyRecord before = legacyCopy();
        NativeRepositoryMigrationOperator.Settings settings = settings();
        NativeMigrationControl control = control(settings, false);
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            NativeRepositoryMigrationOperator.run(settings, held, client, control);
            assertThat(held.record()).isEqualTo(before);
        }
        verify(control).send(eq("prepared"), eq(before), any());
        verify(client, never()).createVolume(any());
        verify(client, never()).open(any());
        verify(client, never()).command(any(), any());
    }

    @Test
    void losingTheControllerBeforeImportCannotCreateNativeResources() throws Exception {
        AccountCopyRecord before = legacyCopy();
        NativeRepositoryMigrationOperator.Settings settings = settings();
        NativeMigrationControl control = control(settings, true);
        doThrow(new WorkerUnavailableException()).when(control).fence(any(), any());
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            assertThatThrownBy(() -> NativeRepositoryMigrationOperator.run(settings, held, client, control))
                    .isInstanceOf(WorkerUnavailableException.class);
            assertThat(held.record()).isEqualTo(before);
        }
        verify(client, never()).createVolume(any());
        verify(client, never()).open(any());
    }

    @Test
    void executionBindsOnlyThePreparedDataWithoutExecutingCommands() throws Exception {
        AccountCopyRecord before = legacyCopy();
        NativeRepositoryMigrationOperator.Settings settings = settings();
        NativeMigrationControl control = control(settings, true);
        try (var held = new AccountCommand(journal.store(), owner, cancellation)) {
            NativeRepositoryMigrationOperator.run(settings, held, client, control);
            assertThat(held.record().writer().storage()).isNotNull();
            assertThat(held.record().state()).isEqualTo(before.state());
            assertThat(held.record().original()).isEqualTo(before.original());
            assertThat(held.record().expiresAt()).isEqualTo(before.expiresAt());
            assertThat(held.record().owner()).isEqualTo(before.owner());
        }
        verify(client, never()).command(any(), any());
        verify(client, never()).events();
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void closedOrMismatchedControlPipesNeverAcknowledgeOwnership() {
        String operation = UUID.randomUUID().toString();
        try (var closed = new NativeMigrationControl(
                operation, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertThatThrownBy(() -> closed.receive("fence")).isInstanceOf(WorkerUnavailableException.class);
        }
        byte[] wrong = (new String(
                                SandcrabProtocol.write(new NativeMigrationControl.Reply(
                                        UUID.randomUUID().toString(), "fence", true, false, null, null)),
                                StandardCharsets.UTF_8)
                        + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (var control =
                new NativeMigrationControl(operation, new ByteArrayInputStream(wrong), new ByteArrayOutputStream())) {
            assertThatThrownBy(() -> control.receive("fence")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private NativeRepositoryMigrationOperator.Settings settings() {
        int uid;
        try {
            uid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        } catch (IOException failure) {
            throw new IllegalStateException("Fixture UID unavailable", failure);
        }
        return new NativeRepositoryMigrationOperator.Settings(
                UUID.randomUUID().toString(),
                uid,
                root,
                new NativeRepositoryMigrationOperator.StoreLimits(
                        128,
                        16 * 1024 * 1024,
                        512L * 1024 * 1024,
                        604_800_000,
                        new RetainedBaseline.Limits(64 * 1024 * 1024, 256 * 1024 * 1024, 100_000)),
                owner,
                journal.record(owner).orElseThrow().copyId(),
                root.resolve("native.sock"),
                "repository",
                8_388_608,
                256,
                root,
                new NativeMigrationControl.Lock(3, 1, 1),
                new NativeMigrationControl.Lock(4, 1, 2));
    }

    private NativeMigrationControl control(NativeRepositoryMigrationOperator.Settings settings, boolean execute)
            throws Exception {
        byte[] bytes = new byte[1024];
        Files.write(root.resolve("work.tar"), bytes);
        Files.write(root.resolve("home.tar"), bytes);
        Files.setPosixFilePermissions(root.resolve("work.tar"), PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(root.resolve("home.tar"), PosixFilePermissions.fromString("rw-------"));
        var descriptor = new NativeRepositoryMigration.Archive(bytes.length, SandcrabProtocol.sha256(bytes));
        NativeRepositoryMigration.Plan plan = execute
                ? NativeRepositoryMigration.plan(
                        journal.record(owner).orElseThrow(),
                        client.info(),
                        descriptor,
                        descriptor,
                        "repository",
                        8_388_608,
                        256)
                : null;
        NativeMigrationControl control = mock(NativeMigrationControl.class);
        when(control.receive("archives"))
                .thenReturn(new NativeMigrationControl.Reply(
                        settings.operation(),
                        "archives",
                        true,
                        false,
                        new NativeMigrationControl.Archives(descriptor, descriptor),
                        plan));
        when(control.receive("plan"))
                .thenReturn(new NativeMigrationControl.Reply(settings.operation(), "plan", true, execute, null, null));
        return control;
    }
}
