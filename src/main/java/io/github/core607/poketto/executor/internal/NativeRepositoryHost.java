package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Opaque calls terminate here: only the application interprets repository identities and save preconditions. */
final class NativeRepositoryHost {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryHost.class);
    private final SelectedFileSaves saves;
    private final SnapshotReader files;
    private final NativeArtifactCommands artifacts;
    private final NativeMediaCommands media;

    NativeRepositoryHost(
            SelectedFileSaves saves,
            SnapshotReader files,
            NativeArtifactCommands artifacts,
            NativeMediaCommands media) {
        this.saves = saves;
        this.files = files;
        this.artifacts = artifacts;
        this.media = media;
    }

    byte[] reply(AuthPrincipal principal, AccountCommand held, NativeRepositoryLease lease, byte[] bytes) {
        lease.authorize();
        Request request = SandcrabProtocol.read(bytes, Request.class);
        BridgeReplies.Reply reply;
        try {
            var workspace = new WorkspaceId(held.owner().workspaceId());
            SelectedFileSaves.State state = held.state();
            reply = switch (request.operation()) {
                case "status" -> status(principal, workspace, request, held);
                case "receipt" ->
                    NativeRepositoryReceipts.page(held.record().state(), BridgeArguments.receipt(request.arguments()));
                case "save" -> save(principal, workspace, held, lease, state, request.arguments());
                case "recover" -> recover(principal, workspace, state, request.arguments());
                case "artifact_create", "artifact_remove" ->
                    artifacts.command(held, lease, request.operation(), request.arguments());
                case "media_list", "media_fetch", "media_import", "media_link" ->
                    media.command(principal, held, lease, request.operation(), request.arguments());
                default -> BridgeReplies.failed("OPERATION_UNAVAILABLE");
            };
        } catch (NativeTransferStore.Capacity capacity) {
            reply = BridgeReplies.failed(
                    request.operation().startsWith("artifact_") ? "ARTIFACT_CAPACITY" : "MATERIALIZE_CAPACITY");
        } catch (AssetStorageException unavailable) {
            reply = BridgeReplies.failedBecause(
                    "MEDIA_UNAVAILABLE", unavailable.reason().name());
        } catch (AuthException denied) {
            reply = BridgeReplies.failed("ACCESS_DENIED");
        } catch (IllegalArgumentException invalid) {
            reply = BridgeReplies.failed("INVALID_SELECTION");
        } catch (SandcrabClient.Rejected refused) {
            if (refused.status() != 404 || !refused.code().equals("FILE_NOT_FOUND")) {
                throw refused;
            }
            reply = BridgeReplies.failed("INVALID_SELECTION");
        } catch (ContentRepositoryException unavailable) {
            reply = BridgeReplies.failed("REPOSITORY_UNAVAILABLE");
        }
        lease.authorize();
        byte[] response = SandcrabProtocol.write(reply);
        if (response.length > SandcrabEventProtocol.CALL_BYTES) {
            throw new WorkerUnavailableException(
                    new IllegalStateException("Repository host reply exceeds its byte budget"));
        }
        return response;
    }

    private BridgeReplies.Reply status(
            AuthPrincipal principal, WorkspaceId workspace, Request request, AccountCommand held) {
        BridgeArguments.Status query = BridgeArguments.status(request.arguments());
        var record = held.record();
        return BridgeReplies.succeeded(new Status(
                record.copyId().toString(),
                record.owner().fullRead() ? "full" : "public",
                record.state().baseCommit(),
                record.state().originalCommit(),
                query.remote() ? remoteStatus(principal, workspace, record) : null,
                record.state().uncertain(),
                NativeRepositoryReceipts.preview(record.state().lastSave()),
                NativeRepositoryReceipts.preview(record.state().lastImport())));
    }

    private BridgeReplies.RemoteStatus remoteStatus(
            AuthPrincipal principal, WorkspaceId workspace, AccountCopyRecord record) {
        if (!record.owner().fullRead()) {
            return new BridgeReplies.RemoteStatus(
                    BridgeReplies.RemoteState.PUBLIC_PROJECTION,
                    record.publicExport().export().commit());
        }
        try {
            Optional<String> commit = saves.currentCommit(principal, workspace);
            return new BridgeReplies.RemoteStatus(
                    commit.filter(record.state().baseCommit()::equals).isPresent()
                            ? BridgeReplies.RemoteState.MATCHES_BASE
                            : BridgeReplies.RemoteState.DIFFERS_FROM_BASE,
                    commit.orElse(null));
        } catch (ContentRepositoryException unavailable) {
            log.warn("Remote main could not be checked; local copy status remains available", unavailable);
            return new BridgeReplies.RemoteStatus(BridgeReplies.RemoteState.UNAVAILABLE, null);
        }
    }

    private BridgeReplies.Reply save(
            AuthPrincipal principal,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            JsonNode arguments) {
        if (!held.owner().fullRead()) {
            return BridgeReplies.failed("ACCESS_DENIED");
        }
        BridgeArguments.Save selected = BridgeArguments.save(arguments);
        Map<String, String> writes = new LinkedHashMap<>();
        long total = 0;
        for (String path : selected.writes()) {
            lease.authorize();
            byte[] captured =
                    files.read(lease, NativeRepositoryLayout.repository(path), ContentLimits.MAX_DOCUMENT_BYTES);
            total += captured.length;
            if (total > 4L * 1024 * 1024) {
                throw new IllegalArgumentException("Selected text exceeds the save bound");
            }
            writes.put(path, text(captured));
        }
        for (String path : selected.deletes()) {
            requireAbsent(lease, NativeRepositoryLayout.repository(path));
        }
        lease.authorize();
        return saves.save(principal, workspace, state, writes, selected.deletes());
    }

    private void requireAbsent(NativeRepositoryLease lease, String path) {
        try {
            files.read(lease, path, 1);
        } catch (SandcrabClient.Rejected missing) {
            if (missing.status() == 404 && missing.code().equals("FILE_NOT_FOUND")) {
                return;
            }
            throw missing;
        }
        throw new IllegalArgumentException("A selected deletion must already be absent from the copy");
    }

    private BridgeReplies.Reply recover(
            AuthPrincipal principal, WorkspaceId workspace, SelectedFileSaves.State state, JsonNode arguments) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("Native save recovery takes no arguments");
        }
        if (state.move != null || state.sync != null) {
            return BridgeReplies.failed("RECOVERY_REQUIRED");
        }
        return saves.recover(principal, workspace, state);
    }

    private static String text(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Selected files must be UTF-8 text without NUL");
            }
            return text;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Selected files must be UTF-8 text", invalid);
        }
    }

    record Request(String operation, JsonNode arguments) {
        Request {
            operation = ProtocolValues.boundedText(operation, 64, "host operation");
            Objects.requireNonNull(arguments, "host arguments must be present");
            ProtocolValues.require(arguments.isObject(), "host arguments", "must be an object");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Status(
            String copyId,
            String scope,
            String baseCommit,
            String initialCommit,
            BridgeReplies.RemoteStatus remote,
            boolean writeOutcomeUnknown,
            NativeRepositoryReceipts.Preview lastSave,
            NativeRepositoryReceipts.Preview lastImport) {}

    @FunctionalInterface
    interface SnapshotReader {
        byte[] read(NativeRepositoryLease lease, String path, int maximum);
    }
}
