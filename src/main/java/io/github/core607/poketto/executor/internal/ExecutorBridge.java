package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireLive;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.executor.internal.SessionFileTransfers.MaterializationCapacity;
import io.github.core607.poketto.mcp.RepositoryExecutor.ArtifactMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Dispatches authenticated in-command CLI requests and translates operation-specific failures. */
final class ExecutorBridge {
    private static final Logger log = LoggerFactory.getLogger(ExecutorBridge.class);
    private final SessionWorker io;
    private final SessionFileTransfers files;
    private final SessionRepositoryCommands repository;
    private final SessionMediaCommands media;
    private final SelectedFileSaves saves;
    private final PortableContentExports packages;

    ExecutorBridge(
            SessionWorker io,
            SessionFileTransfers files,
            SessionRepositoryCommands repository,
            SessionMediaCommands media,
            SelectedFileSaves saves,
            PortableContentExports packages) {
        this.io = io;
        this.files = files;
        this.repository = repository;
        this.media = media;
        this.saves = saves;
        this.packages = packages;
    }

    BridgeReplies.Reply bridgeReply(ExecutionSession session, String executionId, JsonNode request) {
        try {
            return bridgeOperation(session, executionId, request);
        } catch (MaterializationCapacity failure) {
            return BridgeReplies.failed(
                    "MATERIALIZE_CAPACITY",
                    "Insufficient session space. Free local space or export fewer files; existing files were preserved.");
        }
    }

    /**
     * Routes one command to the family that owns it. Each family translates the same exceptions
     * into its own failure codes -- a rejected selection is INVALID_EXPORT_SELECTION, INVALID_ARTIFACT,
     * INVALID_MEDIA_REQUEST or INVALID_SELECTION depending on who asked -- so the translation stays
     * with the family rather than being shared, and the agent inside the sandbox is told which of
     * its arguments was refused.
     */
    private BridgeReplies.Reply bridgeOperation(ExecutionSession session, String executionId, JsonNode request) {
        JsonNode arguments = request.path("arguments");
        if (!arguments.isObject()) {
            throw new WorkerUnavailableException();
        }
        String operation = request.path("operation").asString("");
        // A status call takes no argument. One that carries any is not a status call at all, and
        // falls through to the unknown-operation reply rather than being answered with a guess.
        if (operation.equals("status") && arguments.isEmpty()) {
            return status(session);
        }
        if (operation.equals("export")) {
            return exportCommand(session, executionId, arguments);
        }
        if (operation.equals("edit") || operation.equals("create")) {
            return localTextCommand(session, executionId, operation, arguments);
        }
        if (operation.equals("artifact_create") || operation.equals("artifact_remove")) {
            return artifactCommand(session, executionId, operation, arguments);
        }
        if (operation.equals("media_fetch")
                || operation.equals("media_import")
                || operation.equals("media_link")
                || operation.equals("media_list")) {
            return media.mediaCommand(session, executionId, operation, arguments);
        }
        if (operation.equals("save")
                || operation.equals("recover")
                || operation.equals("sync")
                || operation.equals("move")) {
            return repository.writeCommand(session, executionId, operation, arguments);
        }
        return BridgeReplies.failed("OPERATION_UNAVAILABLE");
    }

    /** Where this session's writes stand, as the agent needs to see them before deciding what to do. */
    private BridgeReplies.Reply status(ExecutionSession session) {
        BridgeReplies.RemoteStatus remote;
        try {
            remote = remoteStatus(session);
        } catch (ContentRepositoryException unavailable) {
            log.warn("Remote head could not be checked; local copy status remains available", unavailable);
            remote = new BridgeReplies.RemoteStatus(BridgeReplies.RemoteState.UNAVAILABLE, null);
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        }
        return BridgeReplies.succeeded(new BridgeReplies.Status(
                session.copyId.toString(),
                session.fullRead ? "full" : "public",
                session.saveState.baseCommit,
                session.gitCommit,
                !session.saveState.baseCommit.equals(session.gitCommit),
                remote,
                session.saveState.uncertain
                        || (session.saveState.move != null && session.saveState.move.result == null),
                session.saveState.move != null,
                session.saveState.move == null
                        ? new BridgeReplies.Absent()
                        : SessionMoves.movePending(session.saveState.move),
                session.saveState.sync != null,
                session.saveState.sync == null
                        ? new BridgeReplies.Absent()
                        : WorkspaceSynchronization.progress(session.saveState.sync),
                session.saveState.lastSave,
                session.saveState.lastImport));
    }

    private BridgeReplies.RemoteStatus remoteStatus(ExecutionSession session) {
        // Public copies use a synthetic commit. The existing public-proof check guards each command;
        // do not reveal an authority commit or compare it with the unrelated projection commit.
        if (!session.fullRead) {
            return new BridgeReplies.RemoteStatus(BridgeReplies.RemoteState.PUBLIC_PROJECTION, session.commit);
        }
        Optional<String> commit = saves.currentCommit(session.principal, session.key.workspace());
        return new BridgeReplies.RemoteStatus(
                commit.filter(session.saveState.baseCommit::equals).isPresent()
                        ? BridgeReplies.RemoteState.MATCHES_BASE
                        : BridgeReplies.RemoteState.DIFFERS_FROM_BASE,
                commit.orElse(null));
    }

    private BridgeReplies.Reply exportCommand(ExecutionSession session, String executionId, JsonNode arguments) {
        try {
            return exportPackage(session, executionId, arguments);
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failed("INVALID_EXPORT_SELECTION");
        } catch (ContentExportException unavailable) {
            return BridgeReplies.failed("EXPORT_" + unavailable.reason().name());
        } catch (ContentRepositoryException | AssetStorageException unavailable) {
            return BridgeReplies.failed("EXPORT_UNAVAILABLE");
        }
    }

    /**
     * Creates or releases one retained artifact. The worker's own refusals pass through unchanged,
     * because the agent distinguishes a missing artifact from a full store from a bad request.
     */
    private BridgeReplies.Reply artifactCommand(
            ExecutionSession session, String executionId, String operation, JsonNode arguments) {
        boolean create = operation.equals("artifact_create");
        try {
            WorkerRequests.Data data;
            if (create) {
                var selected = BridgeArguments.artifactCreate(arguments);
                data = new WorkerRequests.ArtifactCreate(executionId, selected.path(), selected.mediaType());
            } else {
                data = new WorkerRequests.ArtifactRemove(
                        BridgeArguments.artifactRemove(arguments).artifactId());
            }
            io.authorize(session);
            JsonNode result =
                    io.request(session, create ? "ARTIFACT_CREATE" : "ARTIFACT_REMOVE", data, Duration.ofSeconds(10));
            io.authorize(session);
            String code = result.path("code").asString("");
            if (Set.of("ARTIFACT_UNAVAILABLE", "ARTIFACT_CAPACITY", "INVALID_ARTIFACT")
                    .contains(code)) {
                return BridgeReplies.failed(code);
            }
            requireOk(result, session);
            return create ? BridgeReplies.artifact(artifactMetadata(result.path("artifact"))) : BridgeReplies.removed();
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failed("INVALID_ARTIFACT");
        }
    }

    private BridgeReplies.Reply localTextCommand(
            ExecutionSession session, String executionId, String operation, JsonNode arguments) {
        try {
            String path;
            String replacement;
            Optional<String> original;
            if (operation.equals("edit")) {
                var edit = BridgeArguments.edit(arguments);
                path = edit.path();
                original = files.captureOptional(session, executionId, path);
                if (original.isEmpty()) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "NOT_FOUND");
                }
                String text = original.orElseThrow();
                int start = text.indexOf(edit.oldText());
                if (start < 0) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "OLD_TEXT_NOT_FOUND");
                }
                if (text.indexOf(edit.oldText(), start + 1) >= 0) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "AMBIGUOUS_MATCH");
                }
                replacement = text.substring(0, start)
                        + edit.newText()
                        + text.substring(start + edit.oldText().length());
            } else {
                var create = BridgeArguments.create(arguments);
                path = create.path();
                replacement = create.text();
                original = files.captureOptional(session, executionId, path);
                if (original.isPresent()) {
                    return BridgeReplies.failedBecause("EDIT_REJECTED", "ALREADY_EXISTS");
                }
            }
            if (replacement.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(replacement)) {
                return BridgeReplies.failedBecause("EDIT_REJECTED", "INVALID_TEXT");
            }
            return installLocalText(session, executionId, path, original, replacement);
        } catch (InvalidSelectionException invalid) {
            return BridgeReplies.failedBecause("EDIT_REJECTED", InvalidSelectionException.reason(invalid));
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failedBecause("EDIT_REJECTED", "INVALID_INPUT");
        }
    }

    private BridgeReplies.Reply installLocalText(
            ExecutionSession session, String executionId, String path, Optional<String> original, String replacement) {
        byte[] content = replacement.getBytes(StandardCharsets.UTF_8);
        if (content.length > ContentLimits.MAX_DOCUMENT_BYTES) {
            return BridgeReplies.failedBecause("EDIT_REJECTED", "TOO_LARGE");
        }
        boolean installed = files.materialize(
                session,
                executionId,
                path,
                content.length,
                hash(replacement),
                original.map(SessionWorker::hash).orElse(null),
                false,
                false,
                output -> output.write(content));
        return installed
                ? BridgeReplies.succeeded(new BridgeReplies.LocalEditResult(path, false))
                : BridgeReplies.failedBecause("EDIT_REJECTED", "LOCAL_FILE_CHANGED");
    }

    private BridgeReplies.Reply exportPackage(ExecutionSession session, String executionId, JsonNode arguments) {
        var requested = BridgeArguments.export(arguments);
        List<String> selections = requested.paths();
        String output = requested.output();
        synchronized (session) {
            requireLive(session);
        }
        io.authorize(session);
        var selected = SessionExportSelection.resolve(selections, session.fullRead ? null : session.publicExport);
        boolean publicOnly = !session.fullRead || requested.publicOnly();
        var client = Optional.of(session.key.scopeHash());
        try {
            var receipt = packages.create(session.principal, session.key.workspace(), selected, publicOnly, client);
            synchronized (session) {
                requireLive(session);
            }
            io.authorize(session);
            if (!files.materialize(
                    session,
                    executionId,
                    output,
                    receipt.bytes(),
                    receipt.sha256(),
                    null,
                    false,
                    true,
                    sink -> packages.copyTo(
                            session.principal, session.key.workspace(), receipt.handle(), client, sink))) {
                return BridgeReplies.failed(
                        "LOCAL_FILE_CHANGED",
                        "The output path is unavailable or contains different local bytes. Keep it or choose another --output.");
            }
            return BridgeReplies.succeeded(new BridgeReplies.ExportResult(
                    output, receipt.bytes(), receipt.sha256(), publicOnly ? "public" : "private", false));
        } finally {
            // Covers a close event that raced before create registered its build. No package handle
            // is exposed to the sandbox; every command owns only its materialized local result.
            packages.closeClient(session.principal, session.key.workspace(), session.key.scopeHash());
        }
    }

    /** The worker's own report about one artifact; its rules live in the record that carries it. */
    static ArtifactMetadata artifactMetadata(JsonNode value) {
        return WorkerResponses.read(value, ArtifactMetadata.class);
    }
}
