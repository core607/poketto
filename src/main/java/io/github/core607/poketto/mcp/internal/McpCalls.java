package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.auth.AuthRevocation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;

/**
 * Tool calls in flight, keyed by the calling key and the client's JSON-RPC request ID. Without a
 * session, a {@code notifications/cancelled} names its call by that pair: a key can cancel only its
 * own calls, and an ID is reusable once its call has ended. Revocation and shutdown cancel the calls
 * they affect; the executor still enforces revocation on running leases independently.
 */
final class McpCalls {
    private final Map<Key, Call> active = new HashMap<>();
    private boolean closed;

    /** The new call's cancellation, or null when the server is closing or the ID is already in flight. */
    synchronized McpCancellation start(McpIdentities.Identity identity, String requestKey) {
        var key = new Key(identity.principal().subjectId(), requestKey);
        if (closed || active.containsKey(key)) {
            return null;
        }
        var cancellation = new McpCancellation();
        active.put(key, new Call(identity, cancellation));
        return cancellation;
    }

    void finish(McpIdentities.Identity identity, String requestKey, McpCancellation cancellation) {
        cancellation.finish();
        synchronized (this) {
            active.computeIfPresent(
                    new Key(identity.principal().subjectId(), requestKey),
                    (key, call) -> call.cancellation == cancellation ? null : call);
        }
    }

    void cancel(McpIdentities.Identity identity, String requestKey) {
        Call call;
        synchronized (this) {
            call = active.get(new Key(identity.principal().subjectId(), requestKey));
        }
        if (call != null) {
            call.cancellation.cancel();
        }
    }

    @EventListener
    void revoke(AuthRevocation event) {
        var pending = new ArrayList<McpCancellation>();
        synchronized (this) {
            active.values().forEach(call -> {
                var identity = call.identity;
                if (event.workspaceId().equals(identity.workspace())
                        && (event.apiKeyIds().contains(identity.principal().subjectId())
                                || event.accountIds()
                                        .contains(identity.principal().accountId()))) {
                    pending.add(call.cancellation);
                }
            });
        }
        pending.forEach(McpCancellation::cancel);
    }

    void close() {
        ArrayList<Call> pending;
        synchronized (this) {
            closed = true;
            pending = new ArrayList<>(active.values());
        }
        pending.forEach(call -> call.cancellation.cancel());
    }

    /** A bounded, type-tagged JSON-RPC ID, so 1 and "1" stay distinct; null when unusable. */
    static String requestKey(Object value) {
        if (value instanceof String text) {
            return text.length() <= 128 ? "s:" + text : null;
        }
        if (value instanceof Number number && number.toString().length() <= 128) {
            try {
                return "n:"
                        + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private record Key(UUID subject, String request) {}

    private record Call(McpIdentities.Identity identity, McpCancellation cancellation) {}
}
