package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeDurabilityBarriersTests {
    @TempDir
    Path root;

    @Test
    void beforePublicationStopsBeforeTheRealPersistenceDelegate() throws Exception {
        publication("beforepublish", "RUNNING");
    }

    @Test
    void afterPublicationStopsOnlyAfterTheRealPersistenceDelegate() throws Exception {
        publication("afterpublish", "READY");
    }

    private void publication(String scenario, String expected) throws Exception {
        Path path = root.resolve("journal");
        Files.writeString(path, "RUNNING");
        var previous = record(AccountCopyRecord.Phase.RUNNING);
        var next = record(AccountCopyRecord.Phase.READY);
        var observed = new AtomicReference<AccountCopyRecord>();
        var barrier = new NativeDurabilityBarriers(scenario, current -> {
            observed.set(current);
            assertThat(read(path)).isEqualTo(expected);
            throw new Stopped();
        });
        barrier.arm();
        assertThatThrownBy(() -> barrier.write(previous, next, () -> write(path, "READY")))
                .isInstanceOf(Stopped.class);
        assertThat(read(path)).isEqualTo(expected);
        assertThat(observed.get()).isSameAs(expected.equals("RUNNING") ? previous : next);
    }

    @Test
    void otherTransitionsAndUnarmedPublicationReachTheirRealDelegate() throws Exception {
        Path path = root.resolve("journal");
        var barrier = new NativeDurabilityBarriers("beforepublish", ignored -> {
            throw new AssertionError("An unrelated transition reached the process-loss barrier");
        });
        barrier.write(
                record(AccountCopyRecord.Phase.RUNNING),
                record(AccountCopyRecord.Phase.READY),
                () -> write(path, "unarmed"));
        barrier.arm();
        barrier.write(
                record(AccountCopyRecord.Phase.READY),
                record(AccountCopyRecord.Phase.RUNNING),
                () -> write(path, "running"));
        assertThat(read(path)).isEqualTo("running");
    }

    @Test
    void discardBarrierRetainsTheDurableJournalUntilExternalTermination() throws Exception {
        Path path = root.resolve("journal");
        Files.writeString(path, "DISCARDING");
        var barrier = new NativeDurabilityBarriers("discarding", current -> {
            assertThat(read(path)).isEqualTo("DISCARDING");
            throw new Stopped();
        });
        barrier.arm();
        assertThatThrownBy(
                        () -> barrier.remove(record(AccountCopyRecord.Phase.DISCARDING), () -> write(path, "removed")))
                .isInstanceOf(Stopped.class);
        assertThat(read(path)).isEqualTo("DISCARDING");
        assertThatThrownBy(() -> barrier.remove(record(AccountCopyRecord.Phase.READY), () -> write(path, "removed")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AccountCopyRecord record(AccountCopyRecord.Phase phase) {
        var record = mock(AccountCopyRecord.class);
        when(record.phase()).thenReturn(phase);
        return record;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not inspect the checkpoint test file", failure);
        }
    }

    private static void write(Path path, String value) {
        try {
            Files.writeString(path, value);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not persist the checkpoint test file", failure);
        }
    }

    private static final class Stopped extends IllegalStateException {
        Stopped() {
            super("Local checkpoint observation stopped the test delegate");
        }
    }
}
