package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real XFS writes with a scheduling barrier after one owner's staged record has been forced. */
record AccountJournalConcurrencyProbe(Path root) {
    private static final long RECORD_BYTES = 65536;

    void verify() throws Exception {
        independentOwnersPublish();
        interruptedStagingIsOwnerScoped();
    }

    private void independentOwnersPublish() throws Exception {
        var first = new AccountCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID(), true);
        var second = first.forked(UUID.randomUUID());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pending = new AtomicReference<Path>();
        var codec = new RetainedRecordFiles(RECORD_BYTES);
        try (var construction = mockConstruction(
                        RetainedRecordFiles.class,
                        withSettings().defaultAnswer(delegatesTo(codec)),
                        (mock, context) -> doAnswer(call -> {
                                    Path path = call.getArgument(0);
                                    Object record = call.getArgument(1);
                                    String digest = codec.write(path, record, call.getArgument(2));
                                    if (record instanceof StoredAccountCopy stored
                                            && stored.owner().equals(first)
                                            && stored.revision() == 1) {
                                        pending.set(path);
                                        entered.countDown();
                                        assertThat(release.await(30, TimeUnit.SECONDS))
                                                .isTrue();
                                    }
                                    return digest;
                                })
                                .when(mock)
                                .write(any(), any(), anyLong()));
                var executor = Executors.newFixedThreadPool(2)) {
            AccountCopyStore store = store(root.resolve("concurrent"));
            UUID copy = first.newCopyId();
            AccountCopyRecord before = record(store, first, copy, 0);
            AccountCopyRecord after = record(store, first, copy, 1);
            try (var held = store.acquire(first)) {
                held.write(before);
                var publishing = executor.submit(() -> held.write(after));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertBusy(store, first);
                    executor.submit(() -> publishOther(store, first, second, copy, pending.get()))
                            .get(5, TimeUnit.SECONDS);
                    assertThat(pending.get()).exists();
                } finally {
                    release.countDown();
                }
                publishing.get(5, TimeUnit.SECONDS);
            }
            try (var recovered = store(root.resolve("concurrent")).acquire(first)) {
                assertThat(recovered.record()).contains(after);
            }
            assertThat(pending.get()).doesNotExist();
        }
    }

    private static void publishOther(
            AccountCopyStore store,
            AccountCopyRecord.Owner first,
            AccountCopyRecord.Owner second,
            UUID copy,
            Path pending) {
        try (var held = store.acquire(second)) {
            AccountCopyRecord initial = record(store, second, second.newCopyId(), 0);
            held.write(initial);
            held.write(record(store, second, initial.copyId(), 1));
            assertThat(pending).exists();
            assertThat(store.copiesOf(first.accountId(), first.workspaceId())).isEqualTo(2);
            assertThat(store.ownerFor(first, copy.toString())).isEqualTo(first);
            assertThat(store.collectionCandidates(32)).contains(first, second);
            assertThatThrownBy(() -> store.acquire(first.forked(UUID.randomUUID())))
                    .isInstanceOfSatisfying(
                            RetainedCopyException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.LIMIT));
        } catch (IOException failure) {
            throw new AssertionError("Independent publication failed", failure);
        }
    }

    private void interruptedStagingIsOwnerScoped() throws Exception {
        Path storage = root.resolve("interrupted");
        AccountCopyStore store = store(storage);
        var first = new AccountCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID(), true);
        var second = first.forked(UUID.randomUUID());
        AccountCopyRecord record = record(store, first, first.newCopyId(), 0);
        try (var held = store.acquire(first)) {
            held.write(record);
        }
        Path firstPending = storage.resolve(key(first) + ".pending");
        privateFile(firstPending);
        try (var held = store.acquire(second)) {
            assertThat(firstPending).exists();
            assertThat(held.record()).isEmpty();
        }
        try (var recovered = store.acquire(first)) {
            assertThat(firstPending).doesNotExist();
            assertThat(recovered.record()).contains(record);
        }

        // Model a creator killed before its first journal: only its durable lock and staging survive.
        Path secondLock = storage.resolve(key(second) + ".lock");
        Path secondPending = storage.resolve(key(second) + ".pending");
        privateFile(secondLock);
        privateFile(secondPending);
        try (var admitted = store.acquire(first.forked(UUID.randomUUID()))) {
            assertThat(secondLock).doesNotExist();
            assertThat(secondPending).doesNotExist();
            assertThat(admitted.record()).isEmpty();
        }
        try (var recovered = store.acquire(first)) {
            assertThat(recovered.record()).contains(record);
        }
    }

    private static void assertBusy(AccountCopyStore store, AccountCopyRecord.Owner owner) {
        assertThatThrownBy(() -> store.acquire(owner))
                .isInstanceOfSatisfying(
                        RetainedCopyException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.BUSY));
    }

    private static void privateFile(Path path) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private static String key(AccountCopyRecord.Owner owner) {
        String identity = owner.accountId() + ":" + owner.workspaceId() + ":" + owner.fullRead();
        return SessionWorker.hash(owner.fork() == null ? identity : identity + ":" + owner.fork());
    }

    private static AccountCopyRecord record(
            AccountCopyStore store, AccountCopyRecord.Owner owner, UUID copy, long revision) {
        var state = new SelectedFileSaves.State("a".repeat(40));
        state.acknowledgeMove(
                "a".repeat(40),
                Set.of("private/draft.md"),
                Map.of("private/draft.md", RetainedFileBaseline.saved("a".repeat(40), "version " + revision)));
        return new AccountCopyRecord(
                owner,
                copy,
                revision,
                store.nextExpiry(),
                new AccountCopyRecord.Writer(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                AccountCopyRecord.Phase.INITIALIZING,
                null,
                null,
                state.snapshot(),
                null,
                null);
    }

    private static AccountCopyStore store(Path path) {
        return new AccountCopyStore(
                path,
                new AccountCopyStore.Limits(
                        2,
                        RECORD_BYTES,
                        512L * 1024 * 1024,
                        Duration.ofDays(7),
                        new RetainedBaseline.Limits(65536, 1048576, 128)),
                Clock.systemUTC());
    }
}
