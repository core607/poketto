package io.github.core607.poketto.executor.internal;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Socket tests stub storage only, applying the store's journal transition rules in memory; the Linux
 * account probes cover persistence, quotas and recovery.
 */
final class SocketAccountJournal {
    private final ConcurrentHashMap<AccountCopyRecord.Owner, Entry> entries = new ConcurrentHashMap<>();
    private final AccountCopyStore store = mock(AccountCopyStore.class);
    private final AtomicInteger contended = new AtomicInteger();

    SocketAccountJournal() throws IOException {
        when(store.nextExpiry())
                .thenAnswer(
                        call -> System.currentTimeMillis() + Duration.ofDays(7).toMillis());
        when(store.expired(any()))
                .thenAnswer(
                        call -> ((AccountCopyRecord) call.getArgument(0)).expiresAt() <= System.currentTimeMillis());
        when(store.ownerFor(any(), any())).thenAnswer(call -> {
            AccountCopyRecord.Owner allowed = call.getArgument(0);
            String id = call.getArgument(1);
            return entries.entrySet().stream()
                    .filter(entry -> entry.getKey().accountId().equals(allowed.accountId())
                            && entry.getKey().workspaceId().equals(allowed.workspaceId())
                            && (allowed.fullRead() || !entry.getKey().fullRead()))
                    .filter(entry -> entry.getValue().record.get() != null
                            && entry.getValue().record.get().copyId().toString().equals(id))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(allowed);
        });
        when(store.acquire(any())).thenAnswer(call -> acquire(call.getArgument(0)));
    }

    AccountCopyStore store() {
        return store;
    }

    /** How many acquisitions found the account lock held by another request. */
    int contended() {
        return contended.get();
    }

    Optional<AccountCopyRecord> record(AccountCopyRecord.Owner owner) {
        Entry entry = entries.get(owner);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.record.get());
    }

    /** Stands in for journal state that only an interrupted host operation could have left. */
    void rewrite(AccountCopyRecord.Owner owner, UnaryOperator<AccountCopyRecord> change) {
        entries.get(owner).record.updateAndGet(change);
    }

    private AccountCopyStore.Lease acquire(AccountCopyRecord.Owner owner) throws IOException {
        Entry entry = entries.computeIfAbsent(owner, ignored -> new Entry());
        if (!entry.lock.tryLock()) {
            contended.incrementAndGet();
            throw new RetainedCopyException(RetainedCopyException.Reason.BUSY);
        }
        var lease = mock(AccountCopyStore.Lease.class);
        when(lease.record()).thenAnswer(call -> Optional.ofNullable(entry.record.get()));
        when(lease.captureOriginal(any())).thenReturn(new AccountCopyRecord.Original("b".repeat(64), 64, 0));
        doAnswer(call -> {
                    AccountCopyRecord next = call.getArgument(0);
                    AccountCopyStore.requireTransition(owner, entry.record.get(), next, store.nextExpiry());
                    entry.record.set(next);
                    return null;
                })
                .when(lease)
                .write(any());
        doAnswer(call -> {
                    var current = entry.record.get();
                    ProtocolValues.require(
                            current != null
                                    && current.copyId().equals(call.getArgument(0))
                                    && current.phase() == AccountCopyRecord.Phase.DISCARDING,
                            "discard identity",
                            "must match the held copy");
                    entry.record.set(null);
                    return null;
                })
                .when(lease)
                .remove(any());
        doAnswer(call -> {
                    entry.lock.unlock();
                    return null;
                })
                .when(lease)
                .close();
        return lease;
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicReference<AccountCopyRecord> record = new AtomicReference<>();
    }
}
