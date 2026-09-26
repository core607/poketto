package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor.CopyRetention;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/** One admitted command owns the durable account journal and its immutable originals. */
final class AccountCommand implements AutoCloseable {
    private final AccountCopyStore store;
    private final AccountCopyStore.Lease lease;
    private final AccountCopyRecord.Owner owner;
    private boolean resumed;

    AccountCommand(AccountCopyStore store, AccountCopyRecord.Owner owner, ExecutionCancellation cancellation) {
        this.store = store;
        this.owner = owner;
        lease = acquire(store, owner, cancellation);
    }

    private static AccountCopyStore.Lease acquire(
            AccountCopyStore store, AccountCopyRecord.Owner owner, ExecutionCancellation cancellation) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!cancellation.isCancelled()) {
            try {
                return store.acquire(owner);
            } catch (RetainedCopyException busy) {
                if (busy.reason() != RetainedCopyException.Reason.BUSY || System.nanoTime() >= deadline) {
                    throw busy;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetainedCopyException(RetainedCopyException.Reason.BUSY, interrupted);
                }
            }
        }
        throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
    }

    AccountCopyRecord.Owner owner() {
        return owner;
    }

    AccountCopyRecord record() {
        return lease.record().orElse(null);
    }

    /** A discarding copy is gone, and a refreshing one is usable only through its rebuild. */
    void requireLive() {
        if (record() != null
                && (record().phase() == AccountCopyRecord.Phase.DISCARDING
                        || record().phase() == AccountCopyRecord.Phase.REFRESHING)) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        if (record() != null && store.expired(record())) {
            throw new RetainedCopyException(RetainedCopyException.Reason.EXPIRED);
        }
    }

    /**
     * Records a new copy before the worker creates it. A rebuild after a changed publication records
     * {@code REFRESHING} instead of {@code INITIALIZING}, so an interrupted rebuild resumes under the
     * same copy ID rather than being refused as an incomplete initialization.
     *
     * <p>A rebuild replaces the refreshing record of the same copy here, after its export exists, so
     * the journal is without a record only between these local writes. The caller passes
     * {@code REFRESHING} only after the worker confirmed that the old copy's files are gone.
     */
    void initialize(
            UUID copy,
            AccountCopyRecord.Writer writer,
            RetainedSaveState state,
            RepositorySnapshotExports.PublicExport projection,
            Consumer<Consumer<RepositoryFile>> source,
            AccountCopyRecord.Phase phase) {
        ProtocolValues.require(
                phase == AccountCopyRecord.Phase.INITIALIZING || phase == AccountCopyRecord.Phase.REFRESHING,
                "initial copy phase",
                "must be initializing or refreshing");
        if (record() != null && replacesRefreshing(copy, phase)) {
            beginDiscard();
            remove();
        }
        if (record() != null) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        lease.write(new AccountCopyRecord(
                1, owner, copy, 0, store.nextExpiry(), writer, phase, null, null, state, projection, null));
        if (owner.fullRead()) {
            var original = lease.captureOriginal(source);
            var current = record();
            lease.write(new AccountCopyRecord(
                    1,
                    owner,
                    copy,
                    1,
                    current.expiresAt(),
                    writer,
                    current.phase(),
                    null,
                    null,
                    state,
                    projection,
                    original));
        }
    }

    private boolean replacesRefreshing(UUID copy, AccountCopyRecord.Phase phase) {
        return phase == AccountCopyRecord.Phase.REFRESHING
                && record().phase() == AccountCopyRecord.Phase.REFRESHING
                && record().copyId().equals(copy);
    }

    /** Called only after the previous lease is confirmed contained. */
    void bind(AccountCopyRecord.Writer writer) {
        requireLive();
        var current = record();
        resumed |= !current.writer().equals(writer);
        boolean interrupted = current.phase() == AccountCopyRecord.Phase.RUNNING;
        UUID previous = interrupted ? current.executionId() : current.lastInterruptedCommand();
        write(current.state(), writer, AccountCopyRecord.Phase.READY, null, previous, current.expiresAt());
    }

    SelectedFileSaves.State state() {
        return SelectedFileSaves.State.restore(record().state(), this::retain, owner.fullRead() ? lease : null);
    }

    /** Called only after a fresh lease found the copy clean; from here the copy's files are disposable. */
    void beginRefresh() {
        requireLive();
        var current = record();
        write(
                current.state(),
                current.writer(),
                AccountCopyRecord.Phase.REFRESHING,
                null,
                current.lastInterruptedCommand(),
                current.expiresAt());
    }

    /** Called only after the rebuilt copy's worker lease reported READY. */
    void finishRefresh(AccountCopyRecord.Writer writer) {
        var current = record();
        if (current == null || current.phase() != AccountCopyRecord.Phase.REFRESHING) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        write(current.state(), writer, AccountCopyRecord.Phase.READY, null, null, current.expiresAt());
    }

    void begin(UUID executionId) {
        var current = record();
        write(
                current.state(),
                current.writer(),
                AccountCopyRecord.Phase.RUNNING,
                executionId,
                current.lastInterruptedCommand(),
                current.expiresAt());
    }

    void retain(RetainedSaveState state) {
        var current = record();
        write(
                state,
                current.writer(),
                current.phase(),
                current.executionId(),
                current.lastInterruptedCommand(),
                current.expiresAt());
    }

    void refused() {
        var current = record();
        write(
                current.state(),
                current.writer(),
                AccountCopyRecord.Phase.READY,
                null,
                current.lastInterruptedCommand(),
                current.expiresAt());
    }

    void complete(RetainedSaveState state) {
        var current = record();
        write(
                state,
                current.writer(),
                AccountCopyRecord.Phase.READY,
                null,
                current.lastInterruptedCommand(),
                Math.max(current.expiresAt(), store.nextExpiry()));
    }

    CopyRetention view() {
        var current = record();
        return new CopyRetention(current.expiresAt(), resumed, current.lastInterruptedCommand());
    }

    void beginDiscard() {
        if (record().phase() != AccountCopyRecord.Phase.DISCARDING) {
            lease.write(record().discarding());
        }
    }

    void remove() {
        lease.remove(record().copyId());
    }

    private void write(
            RetainedSaveState state,
            AccountCopyRecord.Writer writer,
            AccountCopyRecord.Phase phase,
            UUID execution,
            UUID interrupted,
            long expiresAt) {
        var current = record();
        lease.write(new AccountCopyRecord(
                1,
                owner,
                current.copyId(),
                current.revision() + 1,
                expiresAt,
                writer,
                phase,
                execution,
                interrupted,
                state,
                current.publicExport(),
                current.original()));
    }

    @Override
    public void close() {
        try {
            lease.close();
        } catch (IOException failure) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }
}
