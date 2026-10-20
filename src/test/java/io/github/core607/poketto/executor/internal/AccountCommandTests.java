package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.mcp.ExecutionCancellation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountCommandTests {
    private final AccountCopyRecord.Owner owner =
            new AccountCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID(), true);
    private final AccountCopyRecord.Writer writer = writer();
    private final List<AccountCopyRecord> writes = new ArrayList<>();
    private SocketAccountJournal journal;

    @BeforeEach
    void initialize() throws IOException {
        journal = new SocketAccountJournal();
        try (AccountCommand held = command()) {
            held.initialize(
                    UUID.randomUUID(),
                    writer,
                    new SelectedFileSaves.State("a".repeat(40)).snapshot(),
                    null,
                    consumer -> {},
                    AccountCopyRecord.Phase.INITIALIZING);
        }
        journal.afterWrite(writes::add);
    }

    @Test
    void bindingTheSameReadyWriterKeepsTheAcknowledgedJournal() {
        AccountCopyRecord before = ready();
        try (AccountCommand held = command()) {
            held.bind(writer);
            assertThat(held.record()).isEqualTo(before);
            assertThat(held.view().resumed()).isFalse();
        }
        assertThat(journal.record(owner)).contains(before);
        assertThat(writes).isEmpty();
    }

    @Test
    void aWriterHandoffIsRecordedOnceAndStillReportsResumptionAfterRebinding() {
        AccountCopyRecord before = ready();
        AccountCopyRecord.Writer replacement = writer();
        try (AccountCommand held = command()) {
            held.bind(replacement);
            held.bind(replacement);
            assertThat(held.view().resumed()).isTrue();
        }
        AccountCopyRecord after = journal.record(owner).orElseThrow();
        assertThat(writes).containsExactly(after);
        assertThat(after.writer()).isEqualTo(replacement);
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(after.state()).isEqualTo(before.state());
        assertThat(after.original()).isEqualTo(before.original());
        assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
    }

    @Test
    void theSameWriterStillRecordsAnInterruptedCommandAndKeepsItsReceiptOnReuse() {
        ready();
        var execution = UUID.randomUUID();
        try (AccountCommand held = command()) {
            held.begin(execution);
        }
        writes.clear();
        try (AccountCommand recovered = command()) {
            recovered.bind(writer);
            assertThat(recovered.record().phase()).isEqualTo(AccountCopyRecord.Phase.READY);
            assertThat(recovered.record().executionId()).isNull();
            assertThat(recovered.view().lastInterruptedCommand()).isEqualTo(execution);
        }
        assertThat(writes).hasSize(1);
        AccountCopyRecord recovered = journal.record(owner).orElseThrow();
        writes.clear();
        try (AccountCommand reused = command()) {
            reused.bind(writer);
            assertThat(reused.view().lastInterruptedCommand()).isEqualTo(execution);
        }
        assertThat(journal.record(owner)).contains(recovered);
        assertThat(writes).isEmpty();
    }

    @Test
    void theSameWriterStillPublishesInitializationAsReady() {
        AccountCopyRecord before = journal.record(owner).orElseThrow();
        try (AccountCommand held = command()) {
            held.bind(writer);
        }
        AccountCopyRecord after = journal.record(owner).orElseThrow();
        assertThat(writes).containsExactly(after);
        assertThat(after.phase()).isEqualTo(AccountCopyRecord.Phase.READY);
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(after.original()).isEqualTo(before.original());
    }

    @Test
    void aWarmCommandStillRecordsItsStartAndCompletion() {
        ready();
        var execution = UUID.randomUUID();
        try (AccountCommand held = command()) {
            held.bind(writer);
            held.begin(execution);
            held.complete(held.record().state());
        }
        assertThat(writes)
                .extracting(AccountCopyRecord::phase)
                .containsExactly(AccountCopyRecord.Phase.RUNNING, AccountCopyRecord.Phase.READY);
        assertThat(writes.getFirst().executionId()).isEqualTo(execution);
        assertThat(writes.getLast().executionId()).isNull();
    }

    @Test
    void aReadyWriterDoesNotBypassExpiry() {
        ready();
        doReturn(true).when(journal.store()).expired(any());
        try (AccountCommand held = command()) {
            assertThatThrownBy(() -> held.bind(writer))
                    .isInstanceOfSatisfying(
                            RetainedCopyException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.EXPIRED));
        }
        assertThat(writes).isEmpty();
    }

    @Test
    void aWriterCannotBindADiscardingCopy() {
        ready();
        try (AccountCommand held = command()) {
            held.beginDiscard();
            writes.clear();
            assertThatThrownBy(() -> held.bind(writer))
                    .isInstanceOfSatisfying(
                            RetainedCopyException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.STALE));
        }
        assertThat(writes).isEmpty();
    }

    private AccountCopyRecord ready() {
        try (AccountCommand held = command()) {
            held.bind(writer);
            writes.clear();
            return held.record();
        }
    }

    private AccountCommand command() {
        return new AccountCommand(journal.store(), owner, mock(ExecutionCancellation.class));
    }

    private static AccountCopyRecord.Writer writer() {
        return new AccountCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
