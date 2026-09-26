package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Coordinates remote write receipts with local file and Git baseline installation. */
final class SessionRepositoryCommands {
    private static final Logger log = LoggerFactory.getLogger(SessionRepositoryCommands.class);
    private final SessionWorker io;
    private final SessionFileTransfers files;
    private final SelectedFileSaves saves;
    private final RepositorySnapshotExports exports;
    private final Duration openTimeout;

    SessionRepositoryCommands(
            SessionWorker io,
            SessionFileTransfers files,
            SelectedFileSaves saves,
            RepositorySnapshotExports exports,
            Duration openTimeout) {
        this.io = io;
        this.files = files;
        this.saves = saves;
        this.exports = exports;
        this.openTimeout = openTimeout;
    }

    /**
     * The four commands that reconcile the sandbox with the repository. Three of them write to it;
     * sync only reads, which is why the capability it needs is the lesser one. All four share the
     * full-read scope check and the refusal to start while an earlier write is unresolved, so those
     * stay here and only the command itself differs.
     */
    BridgeReplies.Reply writeCommand(
            ExecutionSession session, String executionId, String operation, JsonNode arguments) {
        if (!session.fullRead) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        try {
            io.authorize(session);
            // Recovery is the one command allowed while a write is unresolved: it exists to resolve
            // one. The guards below would otherwise refuse it and leave the session stuck.
            if (operation.equals("recover")) {
                return finishBaseline(session, executionId, recoverCommand(session, executionId, arguments));
            }
            if (session.saveState.move != null) {
                return SessionMoves.pendingResult(session.saveState.move, "RECOVER_MOVE_FIRST");
            }
            if (session.saveState.uncertain) {
                return BridgeReplies.failed("WRITE_OUTCOME_UNKNOWN");
            }
            if (session.saveState.sync != null && !operation.equals("sync")) {
                return BridgeReplies.failed("RECOVER_SYNC_FIRST");
            }
            if (!installGitBaseline(session, executionId)) {
                return baselinePending();
            }
            var reply =
                    switch (operation) {
                        case "move" -> moveCommand(session, executionId, arguments);
                        case "sync" -> syncCommand(session, executionId, arguments);
                        default -> saveCommand(session, executionId, arguments);
                    };
            return finishBaseline(session, executionId, reply);
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failedBecause("INVALID_SELECTION", InvalidSelectionException.reason(invalid));
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (ContentRepositoryException unavailable) {
            return BridgeReplies.failed("REPOSITORY_UNAVAILABLE");
        }
    }

    private BridgeReplies.Reply finishBaseline(
            ExecutionSession session, String executionId, BridgeReplies.Reply reply) {
        if (!reply.ok() && !"MERGE_CONFLICT".equals(reply.code())) {
            return reply;
        }
        try {
            return installGitBaseline(session, executionId) ? reply : baselinePending();
        } catch (WorkerUnavailableException | ContentRepositoryException pending) {
            log.warn("Acknowledged repository write awaits local Git baseline installation", pending);
            return baselinePending();
        }
    }

    private static BridgeReplies.Reply baselinePending() {
        return BridgeReplies.failed(
                "LOCAL_BASELINE_PENDING",
                "The remote result is retained. Run poketto recover to finish the local Git update; do not repeat the save.");
    }

    void refreshGitOnOpen(ExecutionSession session) {
        try {
            installGitBaseline(session, "");
        } catch (ContentRepositoryException pending) {
            log.warn("Retained Git baseline export is unavailable; local inspection remains available", pending);
        }
    }

    private boolean installGitBaseline(ExecutionSession session, String executionId) {
        if (!session.fullRead || session.saveState.baseCommit.equals(session.gitCommit)) {
            return true;
        }
        io.authorize(session);
        var export = exports.update(
                session.principal, session.key.workspace(), session.gitCommit, session.saveState.baseCommit);
        GitInstallation installed = installGitExport(session, executionId, export);
        if (installed == GitInstallation.MISSING_OBJECTS) {
            var complete = exports.create(
                    session.principal, session.key.workspace(), Optional.of(session.saveState.baseCommit));
            installed = installGitExport(session, executionId, complete);
        }
        return installed == GitInstallation.INSTALLED;
    }

    private GitInstallation installGitExport(
            ExecutionSession session, String executionId, RepositorySnapshotExports.Export export) {
        try {
            if (!export.commit().equals(session.saveState.baseCommit)) {
                throw new WorkerUnavailableException(
                        new IllegalStateException("Baseline export does not match the acknowledged commit"));
            }
            JsonNode answer = io.request(
                    session,
                    "BASELINE",
                    new WorkerRequests.Baseline(
                            executionId,
                            export.exportId(),
                            export.bundleSha256(),
                            export.bundleBytes(),
                            export.commit()),
                    openTimeout);
            if (WorkerResponses.refused(answer, "BASELINE_MISSING_OBJECTS")) {
                return GitInstallation.MISSING_OBJECTS;
            }
            if (WorkerResponses.refused(answer, "BASELINE_UNAVAILABLE")) {
                return GitInstallation.UNAVAILABLE;
            }
            requireOk(answer, session);
            String installed = WorkerResponses.read(answer, WorkerResponses.GitBaseline.class)
                    .gitCommit();
            if (!installed.equals(export.commit())) {
                throw new WorkerUnavailableException(
                        new IllegalStateException("Worker installed another Git baseline"));
            }
            io.authorize(session);
            session.gitCommit = installed;
            return GitInstallation.INSTALLED;
        } finally {
            try {
                exports.release(export.exportId());
            } catch (ContentRepositoryException cleanup) {
                log.warn("Git export cleanup failed; the installation outcome is unchanged", cleanup);
            }
        }
    }

    /**
     * Resumes retained synchronization or reconciles an uncertain remote write. {@code --skip-local}
     * releases an interrupted synchronization or a confirmed move's pending local installation.
     */
    private BridgeReplies.Reply recoverCommand(ExecutionSession session, String executionId, JsonNode arguments) {
        boolean skipLocal = BridgeArguments.recoverSkipsLocal(arguments);
        if (session.saveState.sync != null) {
            return skipLocal ? session.saveState.finishSync(true) : synchronizeWorkspace(session, executionId);
        }
        if (session.saveState.move != null) {
            var recovered = saves.moves().recover(session.principal, session.key.workspace(), session.saveState);
            if (session.saveState.move == null || session.saveState.move.result == null) {
                return recovered;
            }
            if (skipLocal) {
                return saves.moves().skipLocal(session.saveState);
            }
            return moveFiles(session, executionId, session.saveState.move, true);
        }
        if (skipLocal) {
            throw new IllegalArgumentException("no pending synchronization or confirmed move to skip");
        }
        return saves.recover(session.principal, session.key.workspace(), session.saveState);
    }

    private BridgeReplies.Reply moveCommand(ExecutionSession session, String executionId, JsonNode arguments) {
        var selected = BridgeArguments.move(arguments);
        var pending = saves.moves()
                .prepare(
                        session.principal,
                        session.key.workspace(),
                        session.saveState,
                        selected.source(),
                        selected.destination(),
                        files.captureOptional(session, executionId, RepositoryMediaIndex.PATH));
        return moveFiles(session, executionId, pending, false);
    }

    private BridgeReplies.Reply syncCommand(ExecutionSession session, String executionId, JsonNode arguments) {
        BridgeArguments.sync(arguments);
        return synchronizeWorkspace(session, executionId);
    }

    private BridgeReplies.Reply synchronizeWorkspace(ExecutionSession session, String executionId) {
        return new WorkspaceSynchronization(saves)
                .run(
                        session.principal,
                        session.key.workspace(),
                        session.saveState,
                        new WorkspaceSynchronization.Files() {
                            @Override
                            public WorkspaceSynchronization.Local read(String path) {
                                return files.captureSyncFile(session, executionId, path);
                            }

                            @Override
                            public boolean install(PendingWorkspaceSync.File file) {
                                return installSyncFile(session, executionId, file);
                            }
                        });
    }

    private boolean installSyncFile(ExecutionSession session, String executionId, PendingWorkspaceSync.File file) {
        byte[] bytes = file.text() == null ? new byte[0] : file.text().getBytes(StandardCharsets.UTF_8);
        long size = file.blob() == null ? bytes.length : file.blob().bytes();
        String digest = file.blob() == null ? hash(bytes) : file.blob().sha256();
        return files.materialize(
                session, executionId, file.path(), size, digest, file.expectedSha256(), file.delete(), true, output -> {
                    if (file.blob() == null) {
                        output.write(bytes);
                    } else {
                        saves.repository().copyBlob(session.principal, session.key.workspace(), file.blob(), output);
                    }
                });
    }

    private BridgeReplies.Reply saveCommand(ExecutionSession session, String executionId, JsonNode arguments) {
        var selection = BridgeArguments.save(arguments);
        List<String> writes = selection.writes();
        List<String> deletes = selection.deletes();
        var captured = files.capture(session, executionId, writes, deletes);
        requireLive(session);
        io.authorize(session);
        return saves.save(session.principal, session.key.workspace(), session.saveState, captured, deletes);
    }

    private BridgeReplies.Reply moveFiles(
            ExecutionSession session, String executionId, SessionMoves.Pending pending, boolean recovery) {
        byte[] payload = pending.payload;
        JsonNode begun = io.request(
                session,
                "MOVE_BEGIN",
                new WorkerRequests.MoveBegin(executionId, payload.length, hash(payload)),
                Duration.ofSeconds(3));
        if (begun.path("code").asString("").equals("MOVE_REJECTED")) {
            if (recovery) {
                return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
            }
            return BridgeReplies.failed("LOCAL_MOVE_REJECTED");
        }
        requireOk(begun, session);
        String transfer =
                WorkerResponses.read(begun, WorkerResponses.Transfer.class).transferId();
        var reference = new WorkerRequests.Transfer(executionId, transfer);
        try {
            if (!stageMoveChunks(session, executionId, transfer, payload)) {
                return recovery
                        ? SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING")
                        : BridgeReplies.failed("LOCAL_MOVE_REJECTED");
            }
            if (!recovery) {
                JsonNode checked = io.request(session, "MOVE_CHECK", reference, Duration.ofSeconds(15));
                if (checked.path("code").asString("").equals("MOVE_REJECTED")) {
                    return BridgeReplies.failed("LOCAL_MOVE_REJECTED");
                }
                requireOk(checked, session);
                if (!WorkerResponses.read(checked, WorkerResponses.MovePreflight.class)
                        .ready()) {
                    throw new WorkerUnavailableException();
                }
                io.authorize(session);
                var committed =
                        saves.moves().commit(session.principal, session.key.workspace(), session.saveState, pending);
                pending = session.saveState.move;
                if (pending == null || pending.result == null) {
                    return committed;
                }
            }
            return installMove(session, reference, pending);
        } finally {
            try {
                io.request(session, "MOVE_ABORT", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn(
                        "Worker move staging cleanup was not acknowledged; command cleanup will release its slot",
                        cleanupFailure);
            }
        }
    }

    private boolean stageMoveChunks(ExecutionSession session, String executionId, String transfer, byte[] payload) {
        for (int offset = 0; offset < payload.length; offset += 65536) {
            io.authorize(session);
            int end = Math.min(offset + 65536, payload.length);
            JsonNode chunk = io.request(
                    session,
                    "MOVE_CHUNK",
                    new WorkerRequests.TransferChunk(
                            executionId,
                            transfer,
                            offset,
                            Base64.getEncoder().encodeToString(Arrays.copyOfRange(payload, offset, end))),
                    Duration.ofSeconds(3));
            if (chunk.path("code").asString("").equals("MOVE_REJECTED")) {
                return false;
            }
            requireOk(chunk, session);
            if (WorkerResponses.read(chunk, WorkerResponses.TransferProgress.class)
                            .receivedBytes()
                    != end) {
                throw new WorkerUnavailableException();
            }
        }
        return true;
    }

    private BridgeReplies.Reply installMove(
            ExecutionSession session, WorkerRequests.Transfer reference, SessionMoves.Pending pending) {
        io.authorize(session);
        JsonNode installed;
        try {
            installed = io.request(session, "MOVE_COMMIT", reference, Duration.ofSeconds(15));
        } catch (WorkerUnavailableException uncertainInstallation) {
            // Git is acknowledged. Keep the plan so a live worker can reconcile its receipt.
            return SessionMoves.pendingResult(pending, "LOCAL_MOVE_PENDING");
        }
        if (installed.path("code").asString("").equals("MOVE_REJECTED")) {
            return SessionMoves.pendingResult(pending, "LOCAL_MOVE_CONFLICT");
        }
        requireOk(installed, session);
        if (WorkerResponses.read(installed, WorkerResponses.MoveInstallation.class)
                        .installed()
                        .changedPaths()
                != pending.paths.size()) {
            throw new WorkerUnavailableException();
        }
        return saves.moves().installed(session.saveState);
    }

    private enum GitInstallation {
        INSTALLED,
        MISSING_OBJECTS,
        UNAVAILABLE
    }
}
