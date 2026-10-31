package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retains bounded, grant-bound sessions; command authority and cancellation remain with each caller. */
final class NativeRepositoryPool implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryPool.class);
    private final SandcrabClient client;
    private final int maximum;
    private final int maximumStarting;
    private final Consumer<Binding> authorize;
    private final LongSupplier clock;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    NativeRepositoryPool(SandcrabClient client, ExecutionLimits limits, Consumer<Binding> authorize) {
        this(client, limits, authorize, System::nanoTime);
    }

    NativeRepositoryPool(
            SandcrabClient client, ExecutionLimits limits, Consumer<Binding> authorize, LongSupplier clock) {
        this.client = Objects.requireNonNull(client, "native pool client must be present");
        maximum = limits.maxSessions();
        maximumStarting = Math.min(maximum, limits.maxOperations());
        this.authorize = Objects.requireNonNull(authorize, "native pool authorizer must be present");
        this.clock = Objects.requireNonNull(clock, "native pool clock must be present");
    }

    record Key(AccountCopyRecord.Owner owner, UUID copyId) {
        Key {
            Objects.requireNonNull(owner, "pooled copy owner must be present");
            Objects.requireNonNull(copyId, "pooled copy ID must be present");
        }
    }

    record Binding(
            Key key,
            AuthPrincipal principal,
            AccountCopyRecord record,
            SandcrabProtocol.SessionPlan plan,
            SandcrabProtocol.Session session) {}

    private record Authority(UUID subject, UUID account, long version) {
        static Authority of(AuthPrincipal principal, Key key) {
            if (principal.kind() != AuthPrincipal.Kind.API_KEY
                    || !principal.accountId().equals(key.owner().accountId())) {
                throw new SecurityException("A pooled session requires its account's API grant");
            }
            return new Authority(principal.subjectId(), principal.accountId(), principal.credentialVersion());
        }
    }

    Claim claim(
            AuthPrincipal principal,
            AccountCopyRecord.Owner owner,
            UUID copyId,
            AccountCopyRecord record,
            SandcrabProtocol.Info info) {
        var key = new Key(owner, copyId);
        Authority authority = Authority.of(principal, key);
        while (true) {
            Entry discard;
            Claim result = null;
            synchronized (this) {
                if (closed) {
                    throw new WorkerUnavailableException();
                }
                Entry existing = entries.get(key);
                if (existing != null && !existing.epoch.equals(info.serverId())) {
                    release(existing);
                    existing = null;
                }
                if (existing != null && existing.claimed && !existing.retired) {
                    throw refused(ExecutionAdmissionException.Reason.BUSY);
                }
                if (existing != null && reusable(existing, authority, record)) {
                    existing.claimed = true;
                    existing.record = record;
                    result = new Claim(existing, false);
                    discard = null;
                } else if (existing != null) {
                    existing.retired = true;
                    discard = existing;
                } else {
                    discard = reserveRoom();
                    if (discard == null) {
                        var entry = new Entry(key, principal, authority, info);
                        entries.put(key, entry);
                        return new Claim(entry, true);
                    }
                }
            }
            if (result != null) {
                authorize(result.entry, true);
                result.requireUsable();
                return result;
            }
            closeEntry(discard);
        }
    }

    private boolean reusable(Entry entry, Authority authority, AccountCopyRecord record) {
        return !entry.retired
                && !entry.starting
                && !entry.opening
                && record != null
                && record.phase() == AccountCopyRecord.Phase.READY
                && record.owner().equals(entry.key.owner())
                && record.copyId().equals(entry.key.copyId())
                && entry.authority.equals(authority)
                && record.writer().equals(entry.record.writer())
                && entry.session != null
                && entry.session.state().equals("ready")
                && clock.getAsLong() - entry.idleUntil < 0;
    }

    /** Check startup capacity before considering eviction. Uncertain cleanup cannot create a free slot. */
    private Entry reserveRoom() {
        if (entries.values().stream().filter(entry -> entry.starting).count() >= maximumStarting) {
            throw refused(ExecutionAdmissionException.Reason.CAPACITY);
        }
        if (entries.size() < maximum) {
            return null;
        }
        Entry idle = entries.values().stream()
                .filter(entry -> !entry.claimed && !entry.retired)
                .min(Comparator.comparingLong(entry -> entry.idleSince))
                .orElse(null);
        if (idle == null) {
            throw refused(ExecutionAdmissionException.Reason.CAPACITY);
        }
        idle.retired = true;
        return idle;
    }

    private static ExecutionAdmissionException refused(ExecutionAdmissionException.Reason reason) {
        return new ExecutionAdmissionException(reason, false);
    }

    final class Claim implements AutoCloseable {
        private final Entry entry;
        private final boolean fresh;
        private final long generation;
        private boolean finished;

        private Claim(Entry entry, boolean fresh) {
            this.entry = entry;
            this.fresh = fresh;
            generation = ++entry.generation;
        }

        boolean fresh() {
            return fresh;
        }

        boolean contained() {
            synchronized (NativeRepositoryPool.this) {
                return entry.contained;
            }
        }

        SandcrabProtocol.Session session() {
            synchronized (NativeRepositoryPool.this) {
                requireUsable();
                return entry.session;
            }
        }

        AccountCopyRecord.Writer writer() {
            synchronized (NativeRepositoryPool.this) {
                requireUsable();
                return entry.record == null ? null : entry.record.writer();
            }
        }

        /** Called after journaling the writer and before any create request can reach the daemon. */
        void register(SandcrabProtocol.SessionPlan plan, AccountCopyRecord record) {
            synchronized (NativeRepositoryPool.this) {
                requireUsable();
                ProtocolValues.require(fresh && entry.plan == null, "pooled session plan", "must be registered once");
                validate(entry, plan, record, null);
                entry.plan = plan;
                entry.record = record;
                entry.opening = true;
                entry.submitted = true;
                entry.profileTtl = entry.info.profiles().get(plan.profile()).sessionTtlMillis();
            }
        }

        /** Acknowledgement resolves a concurrent close; a retired claim never becomes usable again. */
        void ready(SandcrabProtocol.Session session, AccountCopyRecord record) {
            boolean usable;
            synchronized (NativeRepositoryPool.this) {
                if (entry.generation != generation) {
                    throw new WorkerUnavailableException();
                }
                validate(entry, entry.plan, record, session);
                ProtocolValues.require(session.state().equals("ready"), "pooled session", "must be ready");
                entry.opening = false;
                entry.starting = false;
                entry.acknowledged = true;
                entry.session = session;
                entry.record = record;
                entry.nextCheck = nextCheck(entry);
                usable = !finished && !entry.retired && entries.get(entry.key) == entry;
            }
            if (!usable) {
                closeEntry(entry);
                throw new WorkerUnavailableException();
            }
        }

        void openFailed(boolean mayHaveExecuted) {
            synchronized (NativeRepositoryPool.this) {
                entry.opening = false;
                entry.submitted = entry.acknowledged || mayHaveExecuted;
                entry.retired = true;
            }
            tryClose(entry);
        }

        void requireUsable() {
            synchronized (NativeRepositoryPool.this) {
                boolean usable = !finished
                        && !entry.retired
                        && entries.get(entry.key) == entry
                        && entry.generation == generation;
                if (!usable) {
                    throw new WorkerUnavailableException();
                }
            }
        }

        /** A late cancellation cannot close a later claim, even when it reused this same session. */
        void invalidate() {
            synchronized (NativeRepositoryPool.this) {
                if (entries.get(entry.key) != entry || entry.generation != generation) {
                    return;
                }
                finished = true;
                entry.retired = true;
            }
            closeEntry(entry);
        }

        /** Normal command completion keeps the session tree alive, after the host journal is READY. */
        void retain(AccountCopyRecord record, SandcrabProtocol.Session observedReady) {
            synchronized (NativeRepositoryPool.this) {
                requireUsable();
                validate(entry, entry.plan, record, observedReady);
                ProtocolValues.require(
                        record.phase() == AccountCopyRecord.Phase.READY,
                        "pooled copy journal",
                        "must be ready before retaining a session");
                ProtocolValues.require(observedReady.state().equals("ready"), "pooled session", "must be ready");
                entry.session = observedReady;
                entry.record = record;
                entry.claimed = false;
                entry.idleSince = clock.getAsLong();
                entry.idleUntil =
                        entry.idleSince + Duration.ofMillis(entry.profileTtl).toNanos();
                entry.nextCheck = nextCheck(entry);
                finished = true;
            }
        }

        @Override
        public void close() {
            synchronized (NativeRepositoryPool.this) {
                if (finished) {
                    return;
                }
                finished = true;
                entry.retired = true;
            }
            closeEntry(entry);
        }
    }

    private static void validate(
            Entry entry,
            SandcrabProtocol.SessionPlan plan,
            AccountCopyRecord record,
            SandcrabProtocol.Session session) {
        entry.identity.validate(entry.plan == null ? null : entry.record.writer(), plan, record, session);
    }

    private long nextCheck(Entry entry) {
        return clock.getAsLong()
                + Duration.ofMillis(Math.max(1, entry.plan.ttlMillis() / 3)).toNanos();
    }

    synchronized boolean maintenanceDue() {
        long now = clock.getAsLong();
        return entries.values().stream()
                .anyMatch(entry -> entry.retired
                        ? now - entry.nextCheck >= 0
                        : (!entry.claimed && now - entry.idleUntil >= 0)
                                || (!entry.opening && entry.session != null && now - entry.nextCheck >= 0));
    }

    synchronized void deferMaintenance() {
        long now = clock.getAsLong();
        for (Entry entry : entries.values()) {
            if (entry.plan == null) {
                continue;
            }
            boolean idleExpired = !entry.claimed && now - entry.idleUntil >= 0;
            if (idleExpired) {
                entry.retired = true;
            }
            if (idleExpired || now - entry.nextCheck >= 0) {
                entry.nextCheck = nextCheck(entry);
            }
        }
    }

    void maintain(SandcrabProtocol.Info info) {
        maintain(info, true);
    }

    void maintain(SandcrabProtocol.Info info, boolean retryClosing) {
        List<Entry> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(entries.values());
        }
        for (Entry entry : snapshot) {
            boolean inspect;
            synchronized (this) {
                if (entries.get(entry.key) != entry) {
                    continue;
                }
                if (!entry.epoch.equals(info.serverId())) {
                    release(entry);
                    continue;
                }
                if (!entry.retired && !entry.claimed && clock.getAsLong() - entry.idleUntil >= 0) {
                    entry.retired = true;
                    entry.nextCheck = clock.getAsLong();
                }
                inspect = !entry.retired
                        && !entry.opening
                        && entry.session != null
                        && clock.getAsLong() - entry.nextCheck >= 0;
                if (inspect) {
                    entry.nextCheck = nextCheck(entry);
                }
            }
            if (inspect) {
                inspect(entry);
            }
            if (retiredDue(entry, retryClosing)) {
                tryClose(entry);
            }
        }
    }

    private void inspect(Entry entry) {
        try {
            if (!authorize(entry, false)) {
                return;
            }
            SandcrabProtocol.Session session = client.session(entry.plan.reference());
            boolean renew;
            synchronized (this) {
                renew = entry.claimed
                        && !entry.retired
                        && !session.terminal()
                        && !session.state().equals("closing");
            }
            if (renew) {
                session = client.renew(session, entry.plan.ttlMillis());
            }
            synchronized (this) {
                validate(entry, entry.plan, entry.record, session);
                entry.session = session;
                if (session.terminal() || session.state().equals("closing")) {
                    entry.retired = true;
                    entry.nextCheck = clock.getAsLong();
                }
            }
        } catch (WorkerUnavailableException | SandcrabClient.Rejected | IllegalArgumentException failure) {
            synchronized (this) {
                entry.retired = true;
                entry.nextCheck = clock.getAsLong();
            }
            log.warn("Native repository runtime inspection remains unconfirmed", failure);
        }
    }

    private boolean authorize(Entry entry, boolean propagate) {
        Binding binding;
        synchronized (this) {
            binding = new Binding(entry.key, entry.principal, entry.record, entry.plan, entry.session);
        }
        try {
            authorize.accept(binding);
            return true;
        } catch (RuntimeException failure) {
            synchronized (this) {
                entry.retired = true;
            }
            log.warn("Native repository runtime authorization failed", failure);
            tryClose(entry);
            if (propagate) {
                throw failure;
            }
            return false;
        }
    }

    void closeCopy(UUID copyId) {
        List<Entry> selected = retire(entry -> entry.key.copyId().equals(copyId));
        closeAll(selected);
    }

    void revoked(AuthRevocation event) {
        List<Entry> selected = retire(entry ->
                entry.key.owner().workspaceId().equals(event.workspaceId().value())
                        && (event.accountIds().contains(entry.key.owner().accountId())
                                || event.apiKeyIds().contains(entry.authority.subject())));
        selected.forEach(this::tryClose);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        closeAll(retire(entry -> true));
    }

    private synchronized List<Entry> retire(Predicate<Entry> predicate) {
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (predicate.test(entry)) {
                entry.retired = true;
                selected.add(entry);
            }
        }
        return selected;
    }

    private void closeAll(List<Entry> selected) {
        WorkerUnavailableException failure = null;
        for (Entry entry : selected) {
            try {
                closeEntry(entry);
            } catch (WorkerUnavailableException | SandcrabClient.Rejected uncontained) {
                if (failure == null) {
                    failure = new WorkerUnavailableException(uncontained);
                } else {
                    failure.addSuppressed(uncontained);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void tryClose(Entry entry) {
        try {
            closeEntry(entry);
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            log.warn("Native repository runtime containment remains unconfirmed", failure);
        }
    }

    private void closeEntry(Entry entry) {
        synchronized (this) {
            if (entries.get(entry.key) != entry) {
                return;
            }
            entry.retired = true;
            if (entry.opening || entry.cleaning) {
                entry.nextCheck = nextCheck(entry);
                throw new WorkerUnavailableException();
            }
            if (!entry.submitted) {
                release(entry);
                return;
            }
            entry.cleaning = true;
        }
        try {
            NativeRepositoryLease.close(client, entry.plan.reference(), Duration.ofSeconds(15));
            SandcrabProtocol.Session terminal = client.session(entry.plan.reference());
            if (!terminal.terminal()) {
                throw new WorkerUnavailableException();
            }
            synchronized (this) {
                release(entry);
            }
            try {
                client.forget(terminal);
            } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
                log.warn("Contained native repository session receipt remains", failure);
            }
        } catch (SandcrabClient.Rejected missing) {
            synchronized (this) {
                // Native forget accepts only terminal sessions; a ready acknowledgement excludes a late create.
                boolean forgotten = entry.acknowledged
                        && !entry.opening
                        && missing.status() == 404
                        && missing.code().equals("SESSION_NOT_FOUND");
                if (!forgotten) {
                    throw missing;
                }
                release(entry);
            }
        } finally {
            synchronized (this) {
                entry.cleaning = false;
                if (!entry.contained) {
                    entry.nextCheck = nextCheck(entry);
                }
            }
        }
    }

    private synchronized boolean retiredDue(Entry entry, boolean retryClosing) {
        return entry.retired && (retryClosing || clock.getAsLong() - entry.nextCheck >= 0);
    }

    private void release(Entry entry) {
        entry.retired = true;
        entry.starting = false;
        entry.contained = true;
        entries.remove(entry.key, entry);
    }

    private static final class Entry {
        final Key key;
        final AuthPrincipal principal;
        final Authority authority;
        final SandcrabProtocol.Info info;
        final NativeRepositoryPoolBinding identity;
        final String epoch;
        AccountCopyRecord record;
        SandcrabProtocol.SessionPlan plan;
        SandcrabProtocol.Session session;
        boolean claimed = true;
        boolean starting = true;
        boolean opening;
        boolean submitted;
        boolean acknowledged;
        boolean retired;
        boolean cleaning;
        boolean contained;
        long idleSince;
        long idleUntil;
        long nextCheck;
        long profileTtl;
        long generation;

        Entry(Key key, AuthPrincipal principal, Authority authority, SandcrabProtocol.Info info) {
            this.key = key;
            this.principal = principal;
            this.authority = authority;
            this.info = info;
            identity = new NativeRepositoryPoolBinding(key, authority.subject(), info);
            epoch = info.serverId();
        }
    }
}
