package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Arrays;
import java.util.Base64;

/** Presentation is bounded separately from the complete durable account receipt. */
final class NativeRepositoryReceipts {
    private static final int PREVIEW_BYTES = SandcrabEventProtocol.CALL_BYTES / 4;

    private NativeRepositoryReceipts() {}

    static Preview preview(BridgeReplies.Recorded receipt) {
        byte[] bytes = SandcrabProtocol.write(receipt);
        boolean omitted = bytes.length > PREVIEW_BYTES;
        return new Preview(bytes.length, SandcrabProtocol.sha256(bytes), omitted, omitted ? null : receipt);
    }

    static BridgeReplies.Reply page(RetainedSaveState state, BridgeArguments.Receipt query) {
        byte[] bytes = SandcrabProtocol.write(query.kind().equals("save") ? state.lastSave() : state.lastImport());
        String digest = SandcrabProtocol.sha256(bytes);
        if (query.sha256() != null && !digest.equals(query.sha256())) {
            return BridgeReplies.failed("RECEIPT_CHANGED", "The receipt changed; restart its read from offset zero.");
        }
        ProtocolValues.inRange(query.offset(), 0, bytes.length, "receipt offset");
        int next = Math.min(bytes.length, query.offset() + query.limit());
        String body = Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, query.offset(), next));
        return BridgeReplies.succeeded(
                new Page(query.kind(), bytes.length, digest, query.offset(), next == bytes.length ? null : next, body));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Preview(int bytes, String sha256, boolean omitted, BridgeReplies.Recorded value) {}

    record Page(String kind, int bytes, String sha256, int offset, Integer nextOffset, String base64) {}
}
