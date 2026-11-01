package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises private durable plan bytes on the same Linux filesystem contract as account journals. */
@EnabledOnOs(OS.LINUX)
class AccountPlanFilesTests {
    private static final String OWNER = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);
    private final UUID plan = UUID.randomUUID();

    @TempDir
    Path temporary;

    private Path root;
    private RetainedDirectory directory;
    private AccountPlanFiles files;

    @BeforeEach
    void initialize() throws Exception {
        root = temporary.resolve("plans");
        directory = new RetainedDirectory(root);
        files = new AccountPlanFiles(root, directory, new RetainedBaseline.Limits(4096, 4096, 2));
    }

    @Test
    void completeBinaryOriginalSurvivesRestartAndOnlyItsOwnerCanReadIt() throws Exception {
        byte[] bytes = new byte[4096];
        bytes[0] = (byte) 255;
        bytes[4095] = 42;
        NativePlanFile file = identity(bytes);
        files.publish(OWNER, plan, file, output -> output.write(bytes), () -> {});
        var reopened =
                new AccountPlanFiles(root, new RetainedDirectory(root), new RetainedBaseline.Limits(4096, 4096, 2));
        var output = new ByteArrayOutputStream();
        reopened.copy(OWNER, plan, file, output, () -> {});
        assertThat(output.toByteArray()).containsExactly(bytes);
        assertThatThrownBy(() -> reopened.copy(OTHER, plan, file, new ByteArrayOutputStream(), () -> {}))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> reopened.copy(OWNER, UUID.randomUUID(), file, new ByteArrayOutputStream(), () -> {}))
                .isInstanceOf(IOException.class);
        assertThat(Files.getPosixFilePermissions(stored(file))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void wrongLengthDigestAndRevocationNeverPublishAnAcknowledgedFile() throws Exception {
        byte[] bytes = {1, 2, 3};
        for (NativePlanFile claimed : new NativePlanFile[] {
            new NativePlanFile(2, SandcrabProtocol.sha256(bytes)),
            new NativePlanFile(4, SandcrabProtocol.sha256(bytes)),
            new NativePlanFile(3, "c".repeat(64))
        }) {
            assertThatThrownBy(() -> files.publish(OWNER, plan, claimed, output -> output.write(bytes), () -> {}))
                    .isInstanceOf(IOException.class);
            files.retainOnly(OWNER, null, Set.of());
            assertThat(root).isEmptyDirectory();
        }
        var checked = new AtomicInteger();
        assertThatThrownBy(() -> files.publish(OWNER, plan, identity(bytes), output -> output.write(bytes), () -> {
                    if (checked.incrementAndGet() == 2) {
                        throw new SecurityException("Plan grant revoked before publication");
                    }
                }))
                .isInstanceOf(SecurityException.class);
        files.retainOnly(OWNER, null, Set.of());
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void existingBytesAreVerifiedAndNeverReplacedWithAnotherCapture() throws Exception {
        byte[] bytes = {1, 2, 3};
        NativePlanFile file = identity(bytes);
        files.publish(OWNER, plan, file, output -> output.write(bytes), () -> {});
        files.publish(
                OWNER,
                plan,
                file,
                output -> {
                    throw new AssertionError("Identity reuse must read the stored bytes");
                },
                () -> {});
        Files.write(stored(file), new byte[] {4, 5, 6});
        assertThatThrownBy(() -> files.publish(OWNER, plan, file, output -> output.write(bytes), () -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("digest");
        assertThat(Files.readAllBytes(stored(file))).containsExactly(4, 5, 6);
    }

    @Test
    void byteAndEntryCeilingsRefuseGrowthWithoutRemovingExistingPlans() throws Exception {
        byte[] bytes = new byte[4096];
        NativePlanFile retained = identity(bytes);
        files.publish(OWNER, plan, retained, output -> output.write(bytes), () -> {});
        assertThatThrownBy(
                        () -> files.publish(OWNER, plan, identity(new byte[] {1}), output -> output.write(1), () -> {}))
                .isInstanceOf(RetainedRecordFiles.SizeLimit.class);
        assertThat(stored(retained)).exists();
        var limited = new AccountPlanFiles(root, directory, new RetainedBaseline.Limits(4096, 4096, 1));
        for (int value : new int[] {1, 2}) {
            byte[] content = {(byte) value};
            limited.publish(OTHER, plan, identity(content), output -> output.write(content), () -> {});
        }
        assertThatThrownBy(() ->
                        limited.publish(OTHER, plan, identity(new byte[] {3}), output -> output.write(3), () -> {}))
                .isInstanceOf(RetainedRecordFiles.SizeLimit.class);
        assertThat(stored(retained)).exists();
    }

    @Test
    void releaseRemovesOnlyUnreferencedFilesOfTheLockedOwner() throws Exception {
        NativePlanFile kept = identity(new byte[] {1});
        NativePlanFile dropped = identity(new byte[] {2});
        files.publish(OWNER, plan, kept, output -> output.write(1), () -> {});
        files.publish(OWNER, plan, dropped, output -> output.write(2), () -> {});
        files.publish(OTHER, plan, dropped, output -> output.write(2), () -> {});
        files.retainOnly(OWNER, plan, Set.of(kept.sha256()));
        assertThat(stored(kept)).exists();
        assertThat(stored(dropped)).doesNotExist();
        files.retainOnly(OWNER, null, Set.of());
        assertThat(stored(kept)).doesNotExist();
        var output = new ByteArrayOutputStream();
        files.copy(OTHER, plan, dropped, output, () -> {});
        assertThat(output.toByteArray()).containsExactly(2);
    }

    @Test
    void exposedOrAliasedFilesFailBeforeServingTheirBytes() throws Exception {
        NativePlanFile file = identity(new byte[] {1});
        files.publish(OWNER, plan, file, output -> output.write(1), () -> {});
        Path stored = stored(file);
        Files.setPosixFilePermissions(stored, PosixFilePermissions.fromString("rw-r--r--"));
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> files.copy(OWNER, plan, file, output, () -> {}))
                .isInstanceOf(IOException.class);
        assertThat(output.size()).isZero();
        Files.setPosixFilePermissions(stored, PosixFilePermissions.fromString("rw-------"));
        Files.createLink(root.resolve("unrelated-link"), stored);
        assertThatThrownBy(() -> files.copy(OWNER, plan, file, output, () -> {}))
                .isInstanceOf(IOException.class);
        assertThat(output.size()).isZero();
    }

    private Path stored(NativePlanFile file) {
        return root.resolve(OWNER + ".plan-" + plan + "-" + file.sha256());
    }

    private static NativePlanFile identity(byte[] bytes) {
        return new NativePlanFile(bytes.length, SandcrabProtocol.sha256(bytes));
    }
}
