package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Keeps a public copy on current publication between commands. No caller command runs against a
 * projection that changed after the copy was opened: a copy without local work is discarded and
 * rebuilt under the same copy ID, and a copy with local work or a pending operation is refused
 * and left untouched.
 *
 * <p>Whether a copy is clean is decided here with existing worker operations: a new lease attaches
 * the copy and runs one fixed, read-only Git inspection as its first command, so no shell state or
 * background process of earlier commands can influence or outlive it, and only this class reads its
 * output. The rebuild records its intent in the account journal before the worker discards
 * anything, so an interrupted rebuild resumes under the same ID. The caller holds the account lock
 * throughout, so no other request runs a command or a second rebuild meanwhile.
 */
final class PublicCopyRefresh {
    private static final Logger log = LoggerFactory.getLogger(PublicCopyRefresh.class);
    private static final String CLEAN = "POKETTO_PUBLIC_COPY_CLEAN";
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(30);

    private final SessionRegistry registry;
    private final SessionWorker io;
    private final SessionLifecycle lifecycle;
    private final CopyDisposal disposal;
    private final RepositorySnapshotExports exports;
    private final WorkerClient worker;

    PublicCopyRefresh(
            SessionRegistry registry,
            SessionWorker io,
            SessionLifecycle lifecycle,
            CopyDisposal disposal,
            RepositorySnapshotExports exports,
            WorkerClient worker) {
        this.registry = registry;
        this.io = io;
        this.lifecycle = lifecycle;
        this.disposal = disposal;
        this.exports = exports;
        this.worker = worker;
    }

    /** The session a command may run in, and whether it was rebuilt for this request. */
    record Admitted(ExecutionSession session, boolean refreshed) {}

    /**
     * Returns the admitted session unchanged unless it is a public copy whose publication changed or
     * whose rebuild was interrupted. A failure contains every lease this call opened, clears its busy
     * claim and leaves either the original copy or a journal record the next request resumes.
     */
    Admitted current(ExecutionSession session, AccountCommand held, ExecutionCancellation cancellation) {
        var record = held.record();
        if (session.fullRead || record == null) {
            return new Admitted(session, false);
        }
        var active = new AtomicReference<>(session);
        try {
            boolean resuming = record.phase() == AccountCopyRecord.Phase.REFRESHING;
            if (!resuming
                    && !exports.publicProjectionChanged(
                            session.principal, session.key.workspace(), session.publicExport)) {
                return new Admitted(session, false);
            }
            try (var registration = cancellation.onCancel(() -> lifecycle.stopAndAwait(active.get(), "cancelled"))) {
                if (!resuming) {
                    requireClean(active, held);
                    if (cancellation.isCancelled()) {
                        throw new WorkerUnavailableException();
                    }
                    held.beginRefresh();
                }
                rebuild(active, held);
                return new Admitted(active.get(), true);
            }
        } catch (RuntimeException failure) {
            contain(active.get(), failure);
            throw failure;
        }
    }

    private void requireClean(AtomicReference<ExecutionSession> active, AccountCommand held) {
        ExecutionSession session = active.get();
        var saved = held.record().state();
        if (saved.uncertain() || saved.move() != null || saved.sync() != null) {
            throw ExecutionAdmissionException.publicationChanged(session.copyId.toString());
        }
        // A lease that already ran commands may keep shell state or background processes, so the
        // inspection always runs as the first command of a new lease: a fresh sandbox at the root.
        ExecutionSession probe = session.openAttempted ? lifecycle.replaceLease(session, session.principal) : session;
        probe.projectionCheck = true;
        active.set(probe);
        held.bind(lifecycle.writer(probe));
        probe.accountRecord = held.record();
        lifecycle.attach(probe);
        boolean clean = probe.gitCommit.equals(saved.baseCommit()) && inspectedClean(probe);
        // A lease stopped by cancellation or revocation reports that, not a change of publication.
        requireLive(probe);
        io.authorize(probe);
        if (!clean) {
            throw ExecutionAdmissionException.publicationChanged(session.copyId.toString());
        }
    }

    // The inspection is read-only and its result never reaches the caller, so it is not journaled as
    // a command: an interruption leaves nothing partial to report.
    private boolean inspectedClean(ExecutionSession probe) {
        JsonNode response = io.request(
                probe,
                "EXEC",
                new WorkerRequests.Exec(
                        UUID.randomUUID().toString(),
                        probe.commit,
                        inspection(probe.commit),
                        INSPECTION_TIMEOUT.toMillis()),
                INSPECTION_TIMEOUT.plusSeconds(5));
        requireOk(response, probe);
        JsonNode result = response.path("result");
        var finished = WorkerResponses.read(result, WorkerResponses.Execution.class);
        return result.path("commit").asString("").equals(probe.commit)
                && finished.freshSandbox()
                && finished.reason() == RepositoryExecutor.TerminationReason.NORMAL
                && finished.exitCode() == 0
                && !finished.stdoutTruncated()
                && finished.stdout().equals(CLEAN + "\n");
    }

    /**
     * The fixed inspection, run in a subshell of a fresh sandbox at the repository root. A command
     * can write to three persistent places, the repository, its parent {@code work} directory and
     * {@code $HOME}, and the worker discards all of them with the copy. A fresh copy's home is empty
     * and its work directory holds only the repository, so any other entry there, a tool cache
     * included, makes the copy not clean.
     *
     * <p>In the repository, HEAD and every ref and reflog entry must reach nothing but the projection
     * commit, no index entry may carry an assume-unchanged or skip-worktree flag that would hide a
     * change, and status must report no staged, unstaged, untracked or ignored path. Options pin the
     * repository and disable the configuration that could hide changes or write the index.
     */
    static String inspection(String commit) {
        ProtocolValues.hex(commit, 40, "projection commit");
        return "( g() { command git --no-optional-locks -c core.fsmonitor=false -c core.untrackedCache=false"
                + " -c core.fileMode=true -c core.autocrlf=false -c core.symlinks=true"
                + " --git-dir=.git --work-tree=. \"$@\"; };"
                + " [ -n \"$HOME\" ] && home=$(command ls -A -- \"$HOME\") && [ -z \"$home\" ]"
                + " && work=$(command ls -A -- ..) && [ \"$work\" = repository ]"
                + " && head=$(g rev-parse --verify HEAD) && reach=$(g rev-list --all --reflog --max-count=2)"
                + " && flags=$(g ls-files -v)"
                + " && changes=$(g status --porcelain=v1 --untracked-files=all --ignored)"
                + " && [ \"$head\" = " + commit + " ] && [ \"$reach\" = " + commit + " ] && [ -z \"$changes\" ]"
                + " && ! printf '%s\\n' \"$flags\" | grep -qv '^H '"
                + " && printf '%s\\n' " + CLEAN + " )";
    }

    // The journal holds REFRESHING from here on. The old lease is contained before the worker
    // discards the files, and the rebuilt copy's record replaces the old one only once its export
    // exists, so every interruption leaves a record that the next admission resumes.
    private void rebuild(AtomicReference<ExecutionSession> active, AccountCommand held) {
        ExecutionSession previous = active.get();
        AccountCopyRecord record = held.record();
        lifecycle.stopAndAwait(previous, "session_closed");
        disposal.discardDisk(previous.principal.subjectId(), record, previous.key, worker.hello());
        var rebuilt = new ExecutionSession(previous.key, previous.principal, false, record.copyId(), UUID.randomUUID());
        rebuilt.busy.set(true);
        if (!registry.replace(previous, rebuilt)) {
            throw new ExecutionAdmissionException(ExecutionAdmissionException.Reason.UNAVAILABLE, true);
        }
        previous.busy.set(false);
        active.set(rebuilt);
        lifecycle.initializeCopy(rebuilt, Optional.empty(), held, AccountCopyRecord.Phase.REFRESHING);
        held.finishRefresh(lifecycle.writer(rebuilt));
        rebuilt.accountRecord = held.record();
    }

    private void contain(ExecutionSession session, RuntimeException failure) {
        try {
            lifecycle.stopAndAwait(session, "cancelled");
        } catch (RuntimeException closing) {
            failure.addSuppressed(closing);
            log.warn("Public copy refresh containment remains unconfirmed", closing);
        } finally {
            session.busy.set(false);
        }
    }
}
