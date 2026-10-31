package io.github.core607.poketto.executor.internal;

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
import tools.jackson.databind.JsonNode;

/** Opaque calls terminate here: only the application interprets repository identities and save preconditions. */
final class NativeRepositoryHost {
    private final SelectedFileSaves saves;
    private final SnapshotReader files;

    NativeRepositoryHost(SelectedFileSaves saves, SnapshotReader files) {
        this.saves = saves;
        this.files = files;
    }

    byte[] reply(AuthPrincipal principal, AccountCommand held, NativeRepositoryLease lease, byte[] bytes) {
        lease.authorize();
        Request request = SandcrabProtocol.read(bytes, Request.class);
        BridgeReplies.Reply reply;
        try {
            var workspace = new WorkspaceId(held.owner().workspaceId());
            SelectedFileSaves.State state = held.state();
            reply = switch (request.operation()) {
                case "status" -> status(request, held);
                case "save" -> save(principal, workspace, held, lease, state, request.arguments());
                case "recover" -> recover(principal, workspace, state, request.arguments());
                default -> BridgeReplies.failed("OPERATION_UNAVAILABLE");
            };
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
        return SandcrabProtocol.write(reply);
    }

    private static BridgeReplies.Reply status(Request request, AccountCommand held) {
        if (!request.arguments().isEmpty()) {
            throw new IllegalArgumentException("Status takes no arguments");
        }
        var record = held.record();
        return BridgeReplies.succeeded(new Status(
                record.copyId().toString(),
                record.owner().fullRead() ? "full" : "public",
                record.state().baseCommit(),
                record.state().originalCommit(),
                record.state().uncertain()));
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
            byte[] captured = files.read(lease, "repository/" + path, ContentLimits.MAX_DOCUMENT_BYTES);
            total += captured.length;
            if (total > 4L * 1024 * 1024) {
                throw new IllegalArgumentException("Selected text exceeds the save bound");
            }
            writes.put(path, text(captured));
        }
        for (String path : selected.deletes()) {
            requireAbsent(lease, "repository/" + path);
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

    record Status(String copyId, String scope, String baseCommit, String gitCommit, boolean writeOutcomeUnknown) {}

    @FunctionalInterface
    interface SnapshotReader {
        byte[] read(NativeRepositoryLease lease, String path, int maximum);
    }
}
