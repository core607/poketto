package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardRequest;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardResult;
import io.github.core607.poketto.mcp.RepositoryExecutor.DiscardStatus;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeRepositoryDisposalTests {
    private final SandcrabClient client = mock(SandcrabClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final NativeRepositoryPool pool = mock(NativeRepositoryPool.class);
    private final NativeRepositoryWorkspace workspaces = mock(NativeRepositoryWorkspace.class);
    private final AuthPrincipal principal = mock(AuthPrincipal.class);
    private final ExecutionCancellation cancellation = mock(ExecutionCancellation.class);
    private final UUID account = UUID.randomUUID();
    private final WorkspaceId workspace = WorkspaceId.random();
    private final String epoch = UUID.randomUUID().toString();
    private final List<UUID> removed = new ArrayList<>();
    private SocketAccountJournal journal;
    private NativeRepositoryDisposal disposal;

    @BeforeEach
    void configure() throws IOException {
        journal = new SocketAccountJournal();
        journal.expireAll();
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.accountId()).thenReturn(account);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(client.info())
                .thenReturn(new SandcrabProtocol.Info(
                        1,
                        epoch,
                        Set.of("runs"),
                        Map.of(
                                "repository",
                                new SandcrabProtocol.Profile(1000, 65536, "none", 3000, 8_388_608, true))));
        disposal = disposal(copy -> {
            assertThat(journal.removed()).isPositive();
            removed.add(copy);
        });
    }

    @Test
    void persistsTheFenceBeforePoolForgetAndRemovesTheJournalOnlyAfterDeletion() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        var order = new ArrayList<String>();
        doAnswer(call -> {
                    assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.READY);
                    order.add("fenced");
                    return null;
                })
                .when(workspaces)
                .fence(eq(record), any());
        journal.afterWrite(value -> order.add("journal:" + value.phase()));
        doAnswer(call -> {
                    assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
                    order.add("pool");
                    return null;
                })
                .when(pool)
                .closeCopy(record.copyId());
        SandcrabVolume ready = volume(record, "ready", false);
        SandcrabVolume deleting = volume(record, "deleting", false);
        SandcrabVolume deleted = volume(record, "deleted", false);
        when(client.volume(ready.id())).thenReturn(ready);
        when(client.deleteVolume(ready)).thenAnswer(call -> {
            order.add("delete");
            return deleting;
        });
        when(client.volume(deleting)).thenAnswer(call -> {
            assertThat(journal.record(record.owner())).isPresent();
            assertThat(removed).isEmpty();
            return deleted;
        });
        doAnswer(call -> {
                    assertThat(journal.record(record.owner())).isEmpty();
                    assertThat(removed).containsExactly(record.copyId());
                    return null;
                })
                .when(client)
                .forget(deleted);

        assertThat(discard(record).status()).isEqualTo(DiscardStatus.DISCARDED);
        assertThat(order).containsExactly("fenced", "journal:DISCARDING", "pool", "delete");
        assertThat(discard(record).status()).isEqualTo(DiscardStatus.ABSENT);
        verify(client, times(1)).deleteVolume(any());
    }

    @Test
    void unconfirmedFenceDoesNotPublishDiscardingOrForgetAnOpeningSession() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.ATTACHING, false, true);
        doThrow(new SandcrabClient.Rejected(404, "SESSION_NOT_FOUND"))
                .when(workspaces)
                .fence(eq(record), any());
        assertThatThrownBy(() -> discard(record)).isInstanceOf(ExecutionAdmissionException.class);
        assertThat(current(record)).isEqualTo(record);
        verifyNoInteractions(pool);
        verify(client, never()).volume(anyString());
    }

    @Test
    void durableDiscardRetriesPoolContainmentWithoutRepeatingTheOldFence() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.ATTACHING, false, true);
        var failing = new AtomicBoolean(true);
        doAnswer(call -> {
                    if (failing.get()) {
                        throw new WorkerUnavailableException();
                    }
                    return null;
                })
                .when(pool)
                .closeCopy(record.copyId());
        assertThatThrownBy(() -> discard(record)).isInstanceOf(ExecutionAdmissionException.class);
        assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        verify(client, never()).volume(anyString());
        failing.set(false);
        when(client.volume(record.copyId().toString())).thenReturn(volume(record, "deleted", false));
        assertThat(disposal.collectExpiredCopies()).isEqualTo(1);
        verify(workspaces, times(1)).fence(eq(record), any());
        verify(pool, times(2)).closeCopy(record.copyId());
        assertThat(journal.record(record.owner())).isEmpty();
    }

    @Test
    void aMissingVolumeKeepsItsDiscardJournalForRecovery() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.DISCARDING, true, true);
        when(client.volume(anyString())).thenThrow(new SandcrabClient.Rejected(404, "VOLUME_NOT_FOUND"));
        assertThat(disposal.collectExpiredCopies()).isZero();
        assertThat(current(record)).isEqualTo(record);
        assertThat(removed).isEmpty();
        verifyNoInteractions(workspaces);
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void aDifferentVolumeBindingCannotBeDeleted() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        var wrong = new SandcrabVolume(
                record.copyId().toString(),
                "another",
                "ready",
                null,
                8_388_608,
                256,
                null,
                "a".repeat(64),
                false,
                null);
        when(client.volume(record.copyId().toString())).thenReturn(wrong);
        assertThatThrownBy(() -> discard(record)).isInstanceOf(IllegalArgumentException.class);
        assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void anAttachedVolumeCannotBeDeletedEvenAfterTheRecordedWriterWasFenced() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        when(client.volume(record.copyId().toString())).thenReturn(volume(record, "ready", true));
        assertThatThrownBy(() -> discard(record)).isInstanceOf(ExecutionAdmissionException.class);
        assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        verify(client, never()).deleteVolume(any());
        assertThat(removed).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"creating", "closing", "failed"})
    void nonDeletedStorageNeverReleasesTheJournal(String state) {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.DISCARDING, false, true);
        when(client.volume(record.copyId().toString())).thenReturn(volume(record, state, false));
        assertThat(disposal.collectExpiredCopies()).isZero();
        assertThat(current(record)).isEqualTo(record);
        verify(client, never()).deleteVolume(any());
        verify(client, never()).forget(any(SandcrabVolume.class));
    }

    @Test
    void uncertainDeleteCanResumeFromDeletingWithoutIssuingAnotherDelete() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        SandcrabVolume ready = volume(record, "ready", false);
        SandcrabVolume deleting = volume(record, "deleting", false);
        when(client.volume(ready.id())).thenReturn(ready, deleting);
        when(client.deleteVolume(ready)).thenThrow(new WorkerUnavailableException());
        assertThatThrownBy(() -> discard(record)).isInstanceOf(ExecutionAdmissionException.class);
        assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        when(client.volume(deleting)).thenReturn(volume(record, "deleted", false));
        assertThat(discard(record).status()).isEqualTo(DiscardStatus.DISCARDED);
        verify(client, times(1)).deleteVolume(ready);
        verify(workspaces, times(1)).fence(eq(record), any());
    }

    @Test
    void collectionUsesExpiryRatherThanLegacyIncompleteCopyEligibility() {
        AccountCopyRecord initializing = seed(AccountCopyRecord.Phase.INITIALIZING, false, true);
        AccountCopyRecord attaching = seed(AccountCopyRecord.Phase.ATTACHING, false, true);
        AccountCopyRecord ready = seed(AccountCopyRecord.Phase.READY, false, true);
        AccountCopyRecord legacy = seed(AccountCopyRecord.Phase.READY, true, false);
        for (AccountCopyRecord.Phase phase : List.of(
                AccountCopyRecord.Phase.INITIALIZING,
                AccountCopyRecord.Phase.ATTACHING,
                AccountCopyRecord.Phase.READY)) {
            AccountCopyRecord expired = seed(phase, true, true);
            when(client.volume(expired.copyId().toString())).thenReturn(volume(expired, "deleted", false));
        }
        AccountCopyRecord discarded = seed(AccountCopyRecord.Phase.DISCARDING, false, true);
        when(client.volume(discarded.copyId().toString())).thenReturn(volume(discarded, "deleted", false));
        assertThat(disposal.collectExpiredCopies()).isEqualTo(4);
        for (AccountCopyRecord kept : List.of(initializing, attaching, ready, legacy)) {
            assertThat(current(kept)).isEqualTo(kept);
            verify(pool, never()).closeCopy(kept.copyId());
        }
        verify(auth, never()).authorize(any(), any(), any(Capability[].class));
    }

    @Test
    void aBusyCopyDoesNotBlockCollectionOfAnotherExpiredCopy() {
        AccountCopyRecord busy = seed(AccountCopyRecord.Phase.READY, true, true);
        AccountCopyRecord idle = seed(AccountCopyRecord.Phase.READY, true, true);
        doThrow(new RetainedCopyException(RetainedCopyException.Reason.BUSY))
                .when(journal.store())
                .acquire(busy.owner());
        when(client.volume(idle.copyId().toString())).thenReturn(volume(idle, "deleted", false));
        assertThat(disposal.collectExpiredCopies()).isEqualTo(1);
        assertThat(current(busy)).isEqualTo(busy);
        assertThat(journal.record(idle.owner())).isEmpty();
        verify(pool, never()).closeCopy(busy.copyId());
    }

    @Test
    void explicitDiscardAndExpiryCannotDeleteTheSameCopyConcurrently() throws Exception {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, true, true);
        SandcrabVolume ready = volume(record, "ready", false);
        var deleting = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(client.volume(ready.id())).thenReturn(ready);
        when(client.deleteVolume(ready)).thenAnswer(call -> {
            deleting.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture deletion was not released");
            }
            return volume(record, "deleted", false);
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<DiscardResult> pending = executor.submit(() -> discard(record));
            try {
                assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(disposal.collectExpiredCopies()).isZero();
                assertThat(journal.contended()).isEqualTo(1);
                assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
                verify(client, times(1)).deleteVolume(ready);
            } finally {
                finish.countDown();
            }
            assertThat(pending.get(5, TimeUnit.SECONDS).status()).isEqualTo(DiscardStatus.DISCARDED);
        }
        assertThat(journal.removed()).isEqualTo(1);
    }

    @Test
    void lostReadPermissionDoesNotPreventAnOwnerFromDiscardingAnExactFork() {
        AccountCopyRecord kept = seed(AccountCopyRecord.Phase.READY, false, true);
        AccountCopyRecord discarded = seed(AccountCopyRecord.Phase.READY, false, true);
        when(client.volume(discarded.copyId().toString())).thenReturn(volume(discarded, "deleted", false));
        assertThat(discard(discarded).status()).isEqualTo(DiscardStatus.DISCARDED);
        assertThat(current(kept)).isEqualTo(kept);
        verify(auth, never()).authorize(principal, workspace, Capability.READ_PRIVATE);
        verify(pool, never()).closeCopy(kept.copyId());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        assertThat(discard(kept).status()).isEqualTo(DiscardStatus.ABSENT);
        assertThat(current(kept)).isEqualTo(kept);
    }

    @Test
    void theNativeOwnerDoesNotMigrateOrDeleteALegacyCopy() {
        AccountCopyRecord legacy = seed(AccountCopyRecord.Phase.READY, true, false);
        assertThatThrownBy(() -> discard(legacy))
                .isInstanceOfSatisfying(
                        ExecutionAdmissionException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ExecutionAdmissionException.Reason.RECOVERY_REQUIRED));
        assertThat(current(legacy)).isEqualTo(legacy);
        verifyNoInteractions(pool, workspaces);
        verify(client, never()).volume(anyString());
    }

    @Test
    void cancellationAfterDeletionKeepsTheJournalUntilAnAuthorizedRetry() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        SandcrabVolume ready = volume(record, "ready", false);
        SandcrabVolume deleted = volume(record, "deleted", false);
        when(client.volume(ready.id())).thenReturn(ready, deleted);
        when(client.deleteVolume(ready)).thenAnswer(call -> {
            when(cancellation.isCancelled()).thenReturn(true);
            return deleted;
        });
        assertThatThrownBy(() -> discard(record)).isInstanceOf(ExecutionAdmissionException.class);
        assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        assertThat(removed).isEmpty();
        when(cancellation.isCancelled()).thenReturn(false);
        assertThat(discard(record).status()).isEqualTo(DiscardStatus.DISCARDED);
    }

    @Test
    void revocationBeforeDeleteRetainsTheDiscardJournalAndVolume() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        SandcrabVolume ready = volume(record, "ready", false);
        when(client.volume(ready.id())).thenAnswer(call -> {
            when(auth.authorize(principal, workspace, Capability.EXECUTE_REPOSITORY))
                    .thenThrow(new SecurityException("Fixture grant revoked"));
            return ready;
        });
        assertThatThrownBy(() -> discard(record)).isInstanceOf(SecurityException.class);
        assertThat(current(record).phase()).isEqualTo(AccountCopyRecord.Phase.DISCARDING);
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void postDeletionReceiptCleanupCannotTurnConfirmedDisposalIntoFailure() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, false, true);
        SandcrabVolume deleted = volume(record, "deleted", false);
        when(client.volume(deleted.id())).thenReturn(deleted);
        disposal = disposal(copy -> {
            throw new WorkerUnavailableException();
        });
        doThrow(new WorkerUnavailableException()).when(client).forget(deleted);
        assertThat(discard(record).status()).isEqualTo(DiscardStatus.DISCARDED);
        assertThat(journal.record(record.owner())).isEmpty();
        verify(client).forget(deleted);
    }

    @Test
    void shutdownStopsExplicitAndBackgroundDeletion() {
        AccountCopyRecord record = seed(AccountCopyRecord.Phase.READY, true, true);
        disposal.close();
        assertThatThrownBy(() -> discard(record)).isInstanceOf(WorkerUnavailableException.class);
        assertThat(disposal.collectExpiredCopies()).isZero();
        assertThat(current(record)).isEqualTo(record);
        verifyNoInteractions(pool, workspaces);
        verify(client, never()).volume(anyString());
    }

    private NativeRepositoryDisposal disposal(Consumer<UUID> callback) {
        return new NativeRepositoryDisposal(client, auth, journal.store(), pool, workspaces, callback);
    }

    private DiscardResult discard(AccountCopyRecord record) {
        return disposal.discard(
                principal, workspace, new DiscardRequest(record.copyId().toString()), cancellation);
    }

    private AccountCopyRecord current(AccountCopyRecord record) {
        return journal.record(record.owner()).orElseThrow();
    }

    private AccountCopyRecord seed(AccountCopyRecord.Phase phase, boolean expired, boolean nativeStorage) {
        UUID copy = UUID.randomUUID();
        var owner = new AccountCopyRecord.Owner(account, workspace.value(), true, copy);
        AccountCopyRecord.NativeStorage storage =
                nativeStorage ? new AccountCopyRecord.NativeStorage(copy, "repository", 8_388_608, 256) : null;
        var writer = new AccountCopyRecord.Writer(
                principal.subjectId(), UUID.fromString(epoch), UUID.randomUUID(), UUID.randomUUID(), storage);
        long expiresAt =
                expired ? 1 : System.currentTimeMillis() + Duration.ofDays(7).toMillis();
        var record = new AccountCopyRecord(
                owner,
                copy,
                0,
                expiresAt,
                writer,
                phase,
                null,
                null,
                new SelectedFileSaves.State("a".repeat(40)).snapshot(),
                null,
                new AccountCopyRecord.Original("b".repeat(64), 64, 0));
        try (AccountCopyStore.Lease held = journal.store().acquire(owner)) {
            held.write(record);
        } catch (IOException failure) {
            throw new IllegalStateException("Fixture journal could not close", failure);
        }
        return record;
    }

    private static SandcrabVolume volume(AccountCopyRecord record, String state, boolean attached) {
        return new SandcrabVolume(
                record.copyId().toString(),
                "repository",
                state,
                null,
                8_388_608,
                256,
                null,
                "a".repeat(64),
                attached,
                attached ? UUID.randomUUID().toString() : null);
    }
}
