package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One private journal per account/workspace/read scope, using the existing bounded durable codec. */
final class AccountCopyStore {
    private static final Logger log = LoggerFactory.getLogger(AccountCopyStore.class);
    private final Path root;
    private final RetainedDirectory directory;
    private final RetainedRecordFiles files;
    private final AccountFileBaselines baselines;
    private final AccountPlanFiles plans;
    private final Limits limits;
    private final Clock clock;
    private String collectionCursor = "";

    AccountCopyStore(Path root, Limits limits, Clock clock) {
        ProtocolValues.require(root.isAbsolute(), "account copy root", "must be absolute");
        this.root = root.normalize();
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        files = new RetainedRecordFiles(limits.recordBytes());
        try {
            directory = new RetainedDirectory(this.root);
            var storage = Files.getFileStore(this.root);
            ProtocolValues.require(
                    storage.type().equals("xfs") && storage.getTotalSpace() <= limits.poolBytes(),
                    "metadata storage",
                    "must belong to the bounded XFS executor pool");
        } catch (IOException failure) {
            throw unavailable(failure);
        }
        baselines = new AccountFileBaselines(this.root, directory, files, limits.recordBytes());
        plans = new AccountPlanFiles(this.root, directory, limits.originalFiles());
    }

    RepositoryBaselineLimits traversalLimits() {
        return new RepositoryBaselineLimits(
                limits.originalFiles().entries(),
                Math.min(limits.originalFiles().expandedBytes(), 1024L * 1024 * 1024),
                Duration.ofMinutes(2));
    }

    long nextExpiry() {
        return Math.addExact(clock.millis(), limits.idle().toMillis());
    }

    boolean expired(AccountCopyRecord record) {
        return record.expiresAt() <= clock.millis();
    }

    /** Whether a command started or ended in the copy within the window; both move its expiry forward. */
    boolean recentlyUsed(AccountCopyRecord record, Duration window) {
        return usedWithin(record.expiresAt(), limits.idle(), clock.millis(), window);
    }

    /** The expiry is the latest command start or end plus the idle retention, so that instant is recoverable. */
    static boolean usedWithin(long expiresAt, Duration idle, long now, Duration window) {
        return expiresAt - idle.toMillis() > now - window.toMillis();
    }

    boolean collectible(AccountCopyRecord record) {
        return collectible(record.phase(), record.expiresAt());
    }

    private boolean collectible(AccountCopyRecord.Phase phase, long expiresAt) {
        return expiresAt <= clock.millis()
                || phase == AccountCopyRecord.Phase.DISCARDING
                || phase == AccountCopyRecord.Phase.INITIALIZING;
    }

    List<AccountCopyRecord.Owner> collectionCandidates(int maximum) {
        ProtocolValues.inRange(maximum, 1, 32, "collection batch size");
        try (var index = index()) {
            index.requireValid();
            var names = new ArrayList<String>();
            try (var entries = Files.newDirectoryStream(root, "*.account")) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (!name.matches("[0-9a-f]{64}\\.account") || names.size() >= 1024) {
                        throw new IOException("Invalid account journal inventory");
                    }
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            List<String> selected = names.stream()
                    .filter(name -> name.compareTo(collectionCursor) > 0)
                    .limit(maximum)
                    .toList();
            if (selected.isEmpty()) {
                selected = names.stream().limit(maximum).toList();
            }
            var owners = new ArrayList<AccountCopyRecord.Owner>();
            for (String name : selected) {
                collectionCursor = name;
                Path path = root.resolve(name);
                directory.checkFile(path);
                var record = files.read(path, StoredAccountCopy.class);
                if (!path.equals(recordPath(record.owner()))) {
                    throw new IOException("Account journal owner differs from its address");
                }
                if (collectible(record.phase(), record.expiresAt())) {
                    owners.add(record.owner());
                }
            }
            return List.copyOf(owners);
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * Routing only: the caller must lock the selected owner and check the copy ID again. A named
     * copy is the default copy of a scope the grant may read, or the fork with that ID; anything
     * else routes to the permitted default, whose own check then reports the mismatch.
     */
    AccountCopyRecord.Owner ownerFor(AccountCopyRecord.Owner permitted, String copyId) {
        if (copyId.equals("new")) {
            return permitted;
        }
        UUID named;
        try {
            named = UUID.fromString(copyId);
        } catch (IllegalArgumentException invalid) {
            return permitted;
        }
        var scopes = permitted.fullRead()
                ? List.of(permitted, new AccountCopyRecord.Owner(permitted.accountId(), permitted.workspaceId(), false))
                : List.of(permitted);
        try (var index = index()) {
            index.requireValid();
            for (var scope : scopes) {
                var current = stored(scope);
                if (current != null && current.copyId().equals(named)) {
                    return scope;
                }
                var fork = scope.forked(named);
                if (stored(fork) != null) {
                    return fork;
                }
            }
            return permitted;
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * Fork candidates outside the agent claim. Selection still locks and rereads each candidate:
     * another command may have claimed it since this inventory was read.
     */
    List<AccountCopyRecord.Owner> unclaimedForksOf(AccountCopyRecord.Owner owner, Duration claim) {
        try (var index = index()) {
            index.requireValid();
            var forks = new ArrayList<AccountCopyRecord.Owner>();
            try (var entries = Files.newDirectoryStream(root, "*.account")) {
                for (Path entry : entries) {
                    directory.checkFile(entry);
                    var record = files.read(entry, StoredAccountCopy.class);
                    var stored = record.owner();
                    if (stored.fork() != null
                            && stored.accountId().equals(owner.accountId())
                            && stored.workspaceId().equals(owner.workspaceId())
                            && stored.fullRead() == owner.fullRead()
                            && !usedWithin(record.expiresAt(), limits.idle(), clock.millis(), claim)) {
                        forks.add(stored);
                    }
                }
            }
            return forks;
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    /** How many copies, default and forks in both scopes, this account holds in this workspace. */
    int copiesOf(UUID account, UUID workspace) {
        try (var index = index()) {
            index.requireValid();
            int count = 0;
            try (var entries = Files.newDirectoryStream(root, "*.account")) {
                for (Path entry : entries) {
                    directory.checkFile(entry);
                    var owner = files.read(entry, StoredAccountCopy.class).owner();
                    if (owner.accountId().equals(account) && owner.workspaceId().equals(workspace)) {
                        count++;
                    }
                }
            }
            return count;
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    Lease acquire(AccountCopyRecord.Owner owner) {
        Path lock = root.resolve(key(owner) + ".lock");
        try (var index = index()) {
            index.requireValid();
            boolean newOwner = !Files.exists(lock, NOFOLLOW_LINKS);
            if (newOwner && countOwners() >= limits.copies()) {
                throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
            }
            var held = RetainedFileLocks.acquire(lock, () -> directory.openLock(lock));
            try {
                if (newOwner) {
                    // Recovery must be able to find and lock the owner of any unpublished file.
                    RetainedDirectory.sync(root);
                }
                cleanPending(key(owner));
                var stored = stored(owner);
                if (stored == null) {
                    // A removal deletes its journal before the versions it referenced.
                    baselines.retainOnly(key(owner), null);
                    plans.retainOnly(key(owner), null, Set.of());
                    return new Lease(owner, held, null, null);
                }
                String digest = stored.state().fileBaselines();
                var versions = baselines.read(key(owner), stored.copyId(), digest);
                retainPlanFiles(owner, stored.state().nativePlan());
                return new Lease(owner, held, stored.restore(versions), digest);
            } catch (RuntimeException | IOException failure) {
                held.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw unavailable(failure);
        }
    }

    private StoredAccountCopy stored(AccountCopyRecord.Owner owner) throws IOException {
        Path path = recordPath(owner);
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return null;
        }
        directory.checkFile(path);
        StoredAccountCopy record = files.read(path, StoredAccountCopy.class);
        ProtocolValues.require(record.owner().equals(owner), "copy owner", "must match its journal address");
        return record;
    }

    private RetainedFileLocks.Held index() throws IOException {
        directory.checkRoot();
        Path lock = root.resolve(".index.lock");
        return RetainedFileLocks.await(lock, () -> directory.openLock(lock), Duration.ofSeconds(5));
    }

    private int countOwners() throws IOException {
        int count = 0;
        try (var entries = Files.newDirectoryStream(root, "*.lock")) {
            for (Path path : entries) {
                directory.checkFile(path);
                if (!path.getFileName().toString().equals(".index.lock")) {
                    if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.lock")) {
                        throw new IOException("Invalid owner lock address");
                    }
                    if (removeUnusedLock(path)) {
                        continue;
                    }
                    if (++count > limits.copies()) {
                        throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
                    }
                }
            }
        }
        return count;
    }

    private boolean removeUnusedLock(Path path) throws IOException {
        String name = path.getFileName().toString();
        Path record = root.resolve(name.substring(0, 64) + ".account");
        if (Files.exists(record, NOFOLLOW_LINKS)) {
            return false;
        }
        try (var unused = RetainedFileLocks.acquire(path, () -> directory.openLock(path))) {
            unused.requireValid();
            cleanPending(name.substring(0, 64));
            baselines.retainOnly(name.substring(0, 64), null);
            plans.retainOnly(name.substring(0, 64), null, Set.of());
            Files.delete(path);
            RetainedDirectory.sync(root);
            return true;
        } catch (RetainedCopyException busy) {
            if (busy.reason() != RetainedCopyException.Reason.BUSY) {
                throw busy;
            }
            return false;
        }
    }

    /** Only the owner lock permits deleting this owner's unpublished journal or baseline. */
    private void cleanPending(String ownerKey) throws IOException {
        Path path = root.resolve(ownerKey + ".pending");
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            directory.checkFile(path);
            Files.delete(path);
        }
    }

    private Path recordPath(AccountCopyRecord.Owner owner) {
        return root.resolve(key(owner) + ".account");
    }

    private void retainPlanFiles(AccountCopyRecord.Owner owner, NativeRepositoryPlan plan) throws IOException {
        plans.retainOnly(key(owner), plan == null ? null : plan.id(), plan == null ? Set.of() : plan.retainedDigests());
    }

    /** A default copy keeps its original address; a fork's address adds its copy ID. */
    private static String key(AccountCopyRecord.Owner owner) {
        String base = owner.accountId() + ":" + owner.workspaceId() + ":" + owner.fullRead();
        return SessionWorker.hash(owner.fork() == null ? base : base + ":" + owner.fork());
    }

    private static RetainedCopyException unavailable(IOException failure) {
        return new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
    }

    final class Lease implements OriginalFileLookup, AutoCloseable {
        private final AccountCopyRecord.Owner owner;
        private final RetainedFileLocks.Held held;
        private final AccountOriginalFiles originals;
        private AccountCopyRecord current;
        private String currentBaselines;
        private boolean uncertain;

        private Lease(
                AccountCopyRecord.Owner owner,
                RetainedFileLocks.Held held,
                AccountCopyRecord current,
                String currentBaselines) {
            this.owner = owner;
            this.held = held;
            this.current = current;
            this.currentBaselines = currentBaselines;
            originals = new AccountOriginalFiles(root, key(owner), directory, limits.originalFiles(), this::liveRecord);
        }

        Optional<AccountCopyRecord> record() {
            requireUsable();
            return Optional.ofNullable(current);
        }

        void publishPlanFile(UUID plan, NativePlanFile file, NativeTransferStore.Source source, Runnable authorize) {
            requirePlan(plan);
            try {
                plans.publish(key(owner), plan, file, source, authorize);
            } catch (RetainedRecordFiles.SizeLimit capacity) {
                throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT, capacity);
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        }

        void copyPlanFile(UUID plan, NativePlanFile file, OutputStream output, Runnable authorize) throws IOException {
            requirePlan(plan);
            plans.copy(key(owner), plan, file, output, authorize);
        }

        private void requirePlan(UUID plan) {
            requireUsable();
            ProtocolValues.require(
                    current != null
                            && current.state().nativePlan() != null
                            && current.state().nativePlan().id().equals(plan),
                    "plan identity",
                    "must match the locked copy journal");
        }

        AccountCopyRecord.Original captureOriginal(Consumer<Consumer<RepositoryFile>> source) {
            try {
                return originals.capture(source);
            } catch (IOException failure) {
                uncertain = true;
                throw unavailable(failure);
            }
        }

        @Override
        public RepositoryFile file(AuthPrincipal actor, WorkspaceId workspace, String commit, String path) {
            requireOriginalOwner(actor, workspace, commit);
            try {
                return originals
                        .find(path)
                        .orElseGet(() -> new RepositoryFile(
                                workspace,
                                Optional.of(commit),
                                path,
                                true,
                                Optional.empty(),
                                Optional.empty(),
                                List.of(),
                                false));
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        }

        @Override
        public void visit(AuthPrincipal actor, WorkspaceId workspace, String commit, Consumer<RepositoryFile> sink) {
            requireOriginalOwner(actor, workspace, commit);
            try {
                originals.visit(sink);
            } catch (IOException failure) {
                throw unavailable(failure);
            }
        }

        private void requireOriginalOwner(AuthPrincipal actor, WorkspaceId workspace, String commit) {
            var record = liveRecord();
            ProtocolValues.require(
                    actor.accountId().equals(owner.accountId())
                            && workspace.value().equals(owner.workspaceId())
                            && commit.equals(record.state().originalCommit()),
                    "original owner",
                    "must match the account, workspace and original commit");
        }

        private AccountCopyRecord liveRecord() {
            requireUsable();
            if (current == null) {
                throw new RetainedCopyException(RetainedCopyException.Reason.MISSING);
            }
            if (current.phase() == AccountCopyRecord.Phase.DISCARDING) {
                throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
            }
            if (expired(current)) {
                throw new RetainedCopyException(RetainedCopyException.Reason.EXPIRED);
            }
            return current;
        }

        void write(AccountCopyRecord next) {
            requireUsable();
            requireNext(next);
            boolean releasedPlan = current != null
                    && current.state().nativePlan() != null
                    && next.state().nativePlan() == null;
            Path temporary = root.resolve(key(owner) + ".pending");
            // Only first publication changes inventory membership. Atomic replacement keeps the
            // owner and copy ID; inventory readers lock and reread before acting on mutable state.
            try (var admission = current == null ? index() : null) {
                if (admission != null) {
                    admission.requireValid();
                }
                try {
                    String versions = baselinesFor(next);
                    files.write(temporary, StoredAccountCopy.of(next, versions), limits.recordBytes());
                    directory.checkFile(temporary);
                    Files.move(
                            temporary,
                            recordPath(owner),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    RetainedDirectory.sync(root);
                    String previous = currentBaselines;
                    current = next;
                    currentBaselines = versions;
                    if (!versions.equals(previous)) {
                        discardSuperseded(versions);
                    }
                    if (releasedPlan) {
                        retainPlanFiles(owner, null);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException failure) {
                uncertain = true;
                throw unavailable(failure);
            }
        }

        private void requireNext(AccountCopyRecord next) {
            AccountCopyTransitions.requireTransition(owner, current, next, nextExpiry());
        }

        /** The journal already names {@code kept}; a leftover file is retried by the next change or removal. */
        private void discardSuperseded(String kept) {
            try {
                baselines.retainOnly(key(owner), kept);
            } catch (IOException failure) {
                log.warn("Superseded acknowledged file versions remain after a durable journal write", failure);
            }
        }

        /** Most journal writes keep the acknowledged versions; only a change publishes a new file. */
        private String baselinesFor(AccountCopyRecord next) throws IOException {
            if (current != null
                    && current.copyId().equals(next.copyId())
                    && current.state().fileBaselines().equals(next.state().fileBaselines())) {
                return currentBaselines;
            }
            return baselines.publish(key(owner), next.copyId(), next.state().fileBaselines());
        }

        /** Caller first confirms worker disposal; deleting metadata does not delete disk work. */
        void remove(UUID expectedCopyId) {
            requireUsable();
            ProtocolValues.require(
                    current != null
                            && current.copyId().equals(expectedCopyId)
                            && current.phase() == AccountCopyRecord.Phase.DISCARDING,
                    "discard identity",
                    "must match the held copy");
            try (var index = index()) {
                index.requireValid();
                originals.remove();
                Files.delete(recordPath(owner));
                RetainedDirectory.sync(root);
                current = null;
                currentBaselines = null;
                baselines.retainOnly(key(owner), null);
                retainPlanFiles(owner, null);
            } catch (IOException failure) {
                uncertain = true;
                throw unavailable(failure);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                originals.close();
                if (current == null) {
                    try (var index = index()) {
                        index.requireValid();
                        if (!Files.exists(recordPath(owner), NOFOLLOW_LINKS)) {
                            cleanPending(key(owner));
                            baselines.retainOnly(key(owner), null);
                            retainPlanFiles(owner, null);
                            Files.deleteIfExists(root.resolve(key(owner) + ".lock"));
                            RetainedDirectory.sync(root);
                        }
                    }
                }
            } finally {
                held.close();
            }
        }

        private void requireUsable() {
            held.requireValid();
            if (uncertain) {
                throw new RetainedCopyException(RetainedCopyException.Reason.UNCERTAIN);
            }
        }
    }

    record Limits(int copies, long recordBytes, long poolBytes, Duration idle, RetainedBaseline.Limits originalFiles) {
        Limits {
            Objects.requireNonNull(originalFiles, "original archive bounds must be present");
            ProtocolValues.require(
                    originalFiles.archiveBytes() <= poolBytes, "original archive", "must fit the storage pool");
            ProtocolValues.inRange(copies, 1, 1024, "stored copy count");
            ProtocolValues.inRange(recordBytes, 4096, 64L * 1024 * 1024, "copy journal bytes");
            ProtocolValues.inRange(poolBytes, recordBytes, 1024L * 1024 * 1024 * 1024, "executor pool bytes");
            ProtocolValues.require(
                    idle != null && !idle.isNegative() && !idle.isZero() && idle.compareTo(Duration.ofDays(30)) <= 0,
                    "copy idle duration",
                    "must be positive and at most 30 days");
        }
    }
}
