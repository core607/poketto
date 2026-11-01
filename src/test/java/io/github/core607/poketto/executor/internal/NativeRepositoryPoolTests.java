package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NativeRepositoryPoolTests {
    private final SandcrabClient client = mock(SandcrabClient.class);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicBoolean closeFails = new AtomicBoolean();
    private final Map<String, SandcrabProtocol.Session> sessions = new ConcurrentHashMap<>();
    private final UUID account = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();
    private final String epoch = UUID.randomUUID().toString();

    @BeforeEach
    void configure() {
        when(client.session(any())).thenAnswer(call -> {
            SandcrabProtocol.Reference reference = call.getArgument(0);
            SandcrabProtocol.Session session = sessions.get(reference.id());
            if (session == null) {
                throw new SandcrabClient.Rejected(404, "SESSION_NOT_FOUND");
            }
            return session;
        });
        when(client.close(any())).thenAnswer(call -> {
            if (closeFails.get()) {
                throw new WorkerUnavailableException();
            }
            SandcrabProtocol.Reference reference = call.getArgument(0);
            SandcrabProtocol.Session closed = state(sessions.get(reference.id()), "closed");
            sessions.put(reference.id(), closed);
            return closed;
        });
        when(client.renew(any(), anyLong())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aReadyCopyBorrowsTheSameSessionWithoutRetainingCommandObjects() {
        Fixture fixture = fixture();
        var checked = new ArrayList<NativeRepositoryPool.Binding>();
        try (var pool = pool(2, 1, checked::add)) {
            var first = open(pool, fixture);
            first.retain(fixture.record(), fixture.session());
            assertThat(first.contained()).isFalse();
            first.close();
            try (var next = claim(pool, fixture)) {
                assertThat(next.fresh()).isFalse();
                assertThat(next.session()).isEqualTo(fixture.session());
                assertThat(next.writer()).isEqualTo(fixture.record().writer());
                assertThat(checked).hasSize(1);
                assertThat(checked.getFirst().record()).isEqualTo(fixture.record());
                next.retain(fixture.record(), fixture.session());
            }
            verify(client, never()).close(any());
            verify(client, never()).open(any());
        }
        verify(client).close(fixture.plan().reference());
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void registrationCannotChangeTheJournalSessionEpochOrVolume() {
        Fixture fixture = fixture();
        List<SandcrabProtocol.SessionPlan> mismatched = List.of(
                new SandcrabProtocol.SessionPlan(
                        UUID.randomUUID().toString(),
                        epoch,
                        "repository",
                        3000,
                        fixture.copy().toString()),
                new SandcrabProtocol.SessionPlan(
                        fixture.plan().id(),
                        UUID.randomUUID().toString(),
                        "repository",
                        3000,
                        fixture.copy().toString()),
                new SandcrabProtocol.SessionPlan(
                        fixture.plan().id(),
                        epoch,
                        "repository",
                        3000,
                        UUID.randomUUID().toString()));
        try (var pool = pool(1, 1, ignored -> {})) {
            for (SandcrabProtocol.SessionPlan plan : mismatched) {
                try (var claim = claim(pool, fixture)) {
                    assertThatThrownBy(() -> claim.register(plan, initializing(fixture)))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
            verify(client, never()).open(any());
            verify(client, never()).session(any());
        }
    }

    @Test
    void changedCredentialOrWriterFencesTheOldSessionBeforeAllocatingAnother() {
        for (boolean credential : List.of(false, true)) {
            Fixture fixture = fixture();
            try (var pool = pool(1, 1, ignored -> {})) {
                open(pool, fixture).retain(fixture.record(), fixture.session());
                AuthPrincipal nextPrincipal =
                        credential ? principal(fixture.principal().subjectId(), 2) : fixture.principal();
                AccountCopyRecord nextRecord = credential
                        ? fixture.record()
                        : record(
                                fixture.owner(),
                                fixture.copy(),
                                new AccountCopyRecord.Writer(
                                        fixture.principal().subjectId(),
                                        UUID.fromString(epoch),
                                        application,
                                        UUID.randomUUID(),
                                        fixture.record().writer().storage()),
                                AccountCopyRecord.Phase.READY);
                try (var next = pool.claim(nextPrincipal, fixture.owner(), fixture.copy(), nextRecord, info(epoch))) {
                    assertThat(next.fresh()).isTrue();
                    verify(client).close(fixture.plan().reference());
                }
            }
        }
    }

    @Test
    void ownerScopeIsPartOfTheKeyAndChangedEpochInvalidatesOldClaimsWithoutClosingNewUnits() {
        Fixture fixture = fixture();
        try (var pool = pool(2, 2, ignored -> {})) {
            var old = open(pool, fixture);
            old.retain(fixture.record(), fixture.session());
            var otherOwner = new AccountCopyRecord.Owner(account, UUID.randomUUID(), true, fixture.copy());
            try (var other = pool.claim(fixture.principal(), otherOwner, fixture.copy(), null, info(epoch))) {
                assertThat(other.fresh()).isTrue();
            }
            String restarted = UUID.randomUUID().toString();
            pool.maintain(info(restarted));
            try (var next = pool.claim(
                    fixture.principal(), fixture.owner(), fixture.copy(), fixture.record(), info(restarted))) {
                assertThat(next.fresh()).isTrue();
                old.invalidate();
                next.requireUsable();
            }
            verify(client, never()).close(any());
        }
    }

    @Test
    void anUnknownOpenAndSameEpochMissingRecordRetainBothAdmissionReservations() {
        Fixture fixture = fixture();
        try (var pool = pool(2, 1, ignored -> {})) {
            var claim = claim(pool, fixture);
            claim.register(fixture.plan(), initializing(fixture));
            claim.openFailed(true);
            Fixture second = fixture();
            assertCapacity(() -> claim(pool, second));
            pool.maintain(info(epoch));
            assertCapacity(() -> claim(pool, second));
            String restarted = UUID.randomUUID().toString();
            pool.maintain(info(restarted));
            try (var fresh = pool.claim(second.principal(), second.owner(), second.copy(), null, info(restarted))) {
                assertThat(fresh.fresh()).isTrue();
            }
        }
        verify(client, never()).forget(any(SandcrabProtocol.Session.class));
    }

    @Test
    void aDefinitelyUndispatchedOpenReleasesItsReservationWithoutQueryingTheDaemon() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            var failed = claim(pool, fixture);
            failed.register(fixture.plan(), initializing(fixture));
            failed.openFailed(false);
            assertThat(failed.contained()).isTrue();
            try (var next = claim(pool, fixture())) {
                assertThat(next.fresh()).isTrue();
            }
            verify(client, never()).session(any());
        }
    }

    @Test
    void anotherConsumerMayForgetOnlyAnAlreadyAcknowledgedTerminalSession() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            var acknowledged = open(pool, fixture);
            acknowledged.retain(fixture.record(), fixture.session());
            // The native forget endpoint removes only terminal records, after session-tree containment.
            sessions.remove(fixture.plan().id());
            pool.closeCopy(fixture.copy());
            assertThat(acknowledged.contained()).isTrue();
            try (var next = claim(pool, fixture())) {
                assertThat(next.fresh()).isTrue();
            }
            verify(client, never()).close(any());
        }
    }

    @Test
    void revocationBeforeOpenAcknowledgementKeepsTheSlotAndContainsTheLateSession() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            var opening = claim(pool, fixture);
            opening.register(fixture.plan(), initializing(fixture));
            pool.revoked(new AuthRevocation(
                    new WorkspaceId(workspace),
                    Set.of(),
                    Set.of(fixture.principal().subjectId())));
            assertThat(opening.contained()).isFalse();
            assertCapacity(() -> claim(pool, fixture()));
            verify(client, never()).session(any());
            sessions.put(fixture.plan().id(), fixture.session());
            assertThatThrownBy(() -> opening.ready(fixture.session(), initializing(fixture)))
                    .isInstanceOf(WorkerUnavailableException.class);
            verify(client).close(fixture.plan().reference());
            assertThat(opening.contained()).isTrue();
            try (var next = claim(pool, fixture())) {
                assertThat(next.fresh()).isTrue();
            }
        }
    }

    @Test
    void failedContainmentKeepsTheCopyReservedUntilAConfirmedClose() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            open(pool, fixture).retain(fixture.record(), fixture.session());
            closeFails.set(true);
            assertThatThrownBy(() -> pool.closeCopy(fixture.copy())).isInstanceOf(WorkerUnavailableException.class);
            assertCapacity(() -> claim(pool, fixture()));
            closeFails.set(false);
            pool.maintain(info(epoch));
            try (var next = claim(pool, fixture())) {
                assertThat(next.fresh()).isTrue();
            }
        }
        verify(client, never()).deleteVolume(any());
    }

    @Test
    void busySessionsRenewButIdleSessionsAreReauthorizedAndExpireWithoutRenewal() {
        Fixture fixture = fixture();
        var checks = new ArrayList<NativeRepositoryPool.Binding>();
        try (var pool = pool(1, 1, checks::add)) {
            var active = open(pool, fixture);
            advance(1000);
            pool.maintain(info(epoch));
            verify(client).renew(fixture.session(), 3000);
            active.retain(fixture.record(), fixture.session());
            advance(1000);
            pool.maintain(info(epoch));
            assertThat(checks).hasSize(2);
            verify(client, times(1)).renew(any(), anyLong());
            advance(2000);
            pool.maintain(info(epoch));
            verify(client).close(fixture.plan().reference());
            try (var next = claim(pool, fixture())) {
                assertThat(next.fresh()).isTrue();
            }
        }
    }

    @Test
    void lateCancellationCannotCloseANewerClaimOfTheSameRetainedSession() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            var old = open(pool, fixture);
            old.retain(fixture.record(), fixture.session());
            try (var next = claim(pool, fixture)) {
                old.invalidate();
                next.requireUsable();
                verify(client, never()).close(any());
                next.retain(fixture.record(), fixture.session());
            }
        }
    }

    @Test
    void maintenanceNeedsNoNativeInformationUntilAnEntryIsDue() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            assertThat(pool.maintenanceDue()).isFalse();
            var active = open(pool, fixture);
            assertThat(pool.maintenanceDue()).isFalse();
            advance(999);
            assertThat(pool.maintenanceDue()).isFalse();
            verify(client, never()).session(any());
            advance(1);
            assertThat(pool.maintenanceDue()).isTrue();
            pool.maintain(info(epoch), false);
            assertThat(pool.maintenanceDue()).isFalse();
            active.retain(fixture.record(), fixture.session());
        }
    }

    @Test
    void failedIdleContainmentWaitsForItsProfileIntervalUnlessExplicitlyReconciled() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            open(pool, fixture).retain(fixture.record(), fixture.session());
            advance(3000);
            closeFails.set(true);
            pool.maintain(info(epoch), false);
            verify(client).close(fixture.plan().reference());
            assertThat(pool.maintenanceDue()).isFalse();
            advance(500);
            pool.maintain(info(epoch), false);
            verify(client, times(1)).close(fixture.plan().reference());
            pool.maintain(info(epoch));
            verify(client, times(2)).close(fixture.plan().reference());
            assertThat(pool.maintenanceDue()).isFalse();
            closeFails.set(false);
            advance(1000);
            assertThat(pool.maintenanceDue()).isTrue();
            pool.maintain(info(epoch), false);
            verify(client, times(3)).close(fixture.plan().reference());
            assertThat(pool.maintenanceDue()).isFalse();
        }
    }

    @Test
    void failedInfoLookupDefersBusyChecksAndExpiredIdleCleanupWithoutClaimingContainment() {
        Fixture fixture = fixture();
        try (var pool = pool(1, 1, ignored -> {})) {
            var active = open(pool, fixture);
            advance(1000);
            assertThat(pool.maintenanceDue()).isTrue();
            pool.deferMaintenance();
            assertThat(pool.maintenanceDue()).isFalse();
            active.requireUsable();
            active.retain(fixture.record(), fixture.session());
            advance(3000);
            assertThat(pool.maintenanceDue()).isTrue();
            pool.deferMaintenance();
            assertThat(pool.maintenanceDue()).isFalse();
            assertThat(active.contained()).isFalse();
            verify(client, never()).close(any());
            advance(1000);
            assertThat(pool.maintenanceDue()).isTrue();
            pool.maintain(info(epoch), false);
            assertThat(active.contained()).isTrue();
        }
    }

    @Test
    void startupPressurePreservesWarmSessionsAndDoesNotConsumeAnotherStartingSlot() {
        Fixture warm = fixture();
        Fixture starting = fixture();
        try (var pool = pool(3, 1, ignored -> {})) {
            open(pool, warm).retain(warm.record(), warm.session());
            var opening = claim(pool, starting);
            opening.register(starting.plan(), initializing(starting));
            assertCapacity(() -> claim(pool, fixture()));
            try (var retained = claim(pool, warm)) {
                assertThat(retained.fresh()).isFalse();
                retained.retain(warm.record(), warm.session());
            }
            verify(client, never()).close(any());
            opening.openFailed(false);
        }
    }

    @Test
    void slowContainmentDoesNotHoldThePoolMonitorAcrossNativeIo() throws Exception {
        Fixture fixture = fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    SandcrabProtocol.Session terminal = state(fixture.session(), "closed");
                    sessions.put(terminal.id(), terminal);
                    return terminal;
                })
                .when(client)
                .close(fixture.plan().reference());
        try (var pool = pool(2, 2, ignored -> {});
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            open(pool, fixture).retain(fixture.record(), fixture.session());
            var closing = threads.submit(() -> pool.closeCopy(fixture.copy()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var opening = threads.submit(() -> claim(pool, fixture()));
                try (var unrelated = opening.get(1, TimeUnit.SECONDS)) {
                    assertThat(unrelated.fresh()).isTrue();
                }
            } finally {
                release.countDown();
            }
            closing.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void oneRevokedIdleGrantDoesNotPreventOtherDueSessionsFromBeingChecked() {
        Fixture first = fixture();
        Fixture second = fixture();
        var checked = new ArrayList<UUID>();
        Consumer<NativeRepositoryPool.Binding> authorize = binding -> {
            checked.add(binding.key().copyId());
            if (binding.key().copyId().equals(first.copy())) {
                throw new SecurityException("Fixture grant was revoked");
            }
        };
        try (var pool = pool(2, 2, authorize)) {
            open(pool, first).retain(first.record(), first.session());
            open(pool, second).retain(second.record(), second.session());
            advance(1000);
            pool.maintain(info(epoch));
            assertThat(checked).containsExactly(first.copy(), second.copy());
            verify(client).close(first.plan().reference());
            verify(client, never()).close(second.plan().reference());
        }
    }

    private NativeRepositoryPool pool(int sessions, int starting, Consumer<NativeRepositoryPool.Binding> authorize) {
        return new NativeRepositoryPool(
                client, new ExecutionLimits(sessions, starting, Map.of(), Map.of()), authorize, clock::get);
    }

    private NativeRepositoryPool.Claim claim(NativeRepositoryPool pool, Fixture fixture) {
        return pool.claim(fixture.principal(), fixture.owner(), fixture.copy(), fixture.record(), info(epoch));
    }

    private NativeRepositoryPool.Claim open(NativeRepositoryPool pool, Fixture fixture) {
        NativeRepositoryPool.Claim claim = claim(pool, fixture);
        claim.register(fixture.plan(), initializing(fixture));
        sessions.put(fixture.plan().id(), fixture.session());
        claim.ready(fixture.session(), initializing(fixture));
        return claim;
    }

    private Fixture fixture() {
        UUID copy = UUID.randomUUID();
        var owner = new AccountCopyRecord.Owner(account, workspace, true, copy);
        AuthPrincipal principal = principal(UUID.randomUUID(), 1);
        var storage = new AccountCopyRecord.NativeStorage(copy, "repository", 8_388_608, 256);
        var writer = new AccountCopyRecord.Writer(
                principal.subjectId(), UUID.fromString(epoch), application, UUID.randomUUID(), storage);
        var plan = new SandcrabProtocol.SessionPlan(
                writer.leaseId().toString(), epoch, "repository", 3000, copy.toString());
        var session = new SandcrabProtocol.Session(
                plan.id(), epoch, "repository", "ready", 10_000, null, copy.toString(), null, null);
        return new Fixture(
                principal, owner, copy, record(owner, copy, writer, AccountCopyRecord.Phase.READY), plan, session);
    }

    private AuthPrincipal principal(UUID subject, long version) {
        AuthPrincipal principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(subject);
        when(principal.accountId()).thenReturn(account);
        when(principal.credentialVersion()).thenReturn(version);
        return principal;
    }

    private static AccountCopyRecord initializing(Fixture fixture) {
        return record(fixture.owner(), fixture.copy(), fixture.record().writer(), AccountCopyRecord.Phase.INITIALIZING);
    }

    private static AccountCopyRecord record(
            AccountCopyRecord.Owner owner, UUID copy, AccountCopyRecord.Writer writer, AccountCopyRecord.Phase phase) {
        return new AccountCopyRecord(
                owner,
                copy,
                1,
                100_000,
                writer,
                phase,
                null,
                null,
                new SelectedFileSaves.State("a".repeat(40)).snapshot(),
                null,
                new AccountCopyRecord.Original("b".repeat(64), 64, 0));
    }

    private SandcrabProtocol.Info info(String epoch) {
        return new SandcrabProtocol.Info(
                1,
                epoch,
                Set.of("runs", "sessions"),
                Map.of("repository", new SandcrabProtocol.Profile(1000, 65536, "none", 3000, 8_388_608, true)));
    }

    private void advance(long millis) {
        clock.addAndGet(Duration.ofMillis(millis).toNanos());
    }

    private static SandcrabProtocol.Session state(SandcrabProtocol.Session session, String state) {
        return new SandcrabProtocol.Session(
                session.id(),
                session.serverId(),
                session.profile(),
                state,
                session.expiresAtMillis(),
                null,
                session.volumeId(),
                null,
                null);
    }

    private static void assertCapacity(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ExecutionAdmissionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));
    }

    private record Fixture(
            AuthPrincipal principal,
            AccountCopyRecord.Owner owner,
            UUID copy,
            AccountCopyRecord record,
            SandcrabProtocol.SessionPlan plan,
            SandcrabProtocol.Session session) {}
}
