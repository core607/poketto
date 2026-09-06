package io.github.core607.poketto.mcp.internal;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Adds cancellation to SDK 2.0 sessions, whose default notification table omits notifications/cancelled. */
final class CancellableMcpSession extends McpStreamableServerSession {
    private final McpStreamableServerSession delegate;
    private final Map<String, McpCancellation> active = new HashMap<>();
    private boolean closed;

    CancellableMcpSession(McpStreamableServerSession delegate, McpSchema.InitializeRequest initialize) {
        super(
                delegate.getId(),
                initialize.capabilities(),
                initialize.clientInfo(),
                Duration.ofSeconds(120),
                Map.of(),
                Map.of());
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> responseStream(McpSchema.JSONRPCRequest request, McpStreamableServerTransport transport) {
        if (!request.method().equals("tools/call")) return delegate.responseStream(request, transport);
        return Mono.deferContextual(context -> {
            String key = requestKey(request.id());
            var cancellation = new McpCancellation();
            synchronized (this) {
                if (closed || key == null || active.containsKey(key) || active.size() >= 4) {
                    return transport.sendMessage(McpSchema.JSONRPCResponse.error(
                            request.id(),
                            new McpSchema.JSONRPCResponse.JSONRPCError(
                                    -32600, "MCP request id or capacity unavailable", null)));
                }
                active.put(key, cancellation);
            }
            McpTransportContext previous = context.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
            McpTransportContext decorated =
                    name -> name.equals(McpCancellation.CONTEXT_KEY) ? cancellation : previous.get(name);
            return delegate.responseStream(request, transport)
                    .contextWrite(current -> current.put(McpTransportContext.KEY, decorated))
                    .doOnCancel(() -> {
                        cancellation.cancel();
                        finish(key, cancellation);
                    })
                    .doOnTerminate(() -> finish(key, cancellation));
        });
    }

    private void finish(String key, McpCancellation cancellation) {
        cancellation.finish();
        synchronized (this) {
            active.remove(key, cancellation);
        }
    }

    @Override
    public Mono<Void> accept(McpSchema.JSONRPCNotification notification) {
        if (!notification.method().equals("notifications/cancelled")) return delegate.accept(notification);
        return Mono.fromRunnable(() -> {
            if (!(notification.params() instanceof Map<?, ?> params)) return;
            String key = requestKey(params.get("requestId"));
            McpCancellation cancellation;
            synchronized (this) {
                cancellation = active.get(key);
            }
            if (cancellation != null) cancellation.cancel();
        });
    }

    private void cancelAll() {
        Map<String, McpCancellation> pending;
        synchronized (this) {
            closed = true;
            pending = Map.copyOf(active);
            active.clear();
        }
        pending.values().forEach(McpCancellation::cancel);
    }

    private static String requestKey(Object value) {
        if (value instanceof String text) return text.length() <= 128 ? "s:" + text : null;
        if (value instanceof Number number && number.toString().length() <= 128) {
            try {
                return "n:"
                        + new BigDecimal(number.toString()).stripTrailingZeros().toString();
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public <T> Mono<T> sendRequest(String method, Object params, TypeRef<T> type) {
        return delegate.sendRequest(method, params, type);
    }

    @Override
    public Mono<Void> sendNotification(String method, Object params) {
        return delegate.sendNotification(method, params);
    }

    @Override
    public Mono<Void> accept(McpSchema.JSONRPCResponse response) {
        return delegate.accept(response);
    }

    @Override
    public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport transport) {
        return delegate.listeningStream(transport);
    }

    @Override
    public Flux<McpSchema.JSONRPCMessage> replay(Object eventId) {
        return delegate.replay(eventId);
    }

    @Override
    public void setMinLoggingLevel(McpSchema.LoggingLevel level) {
        delegate.setMinLoggingLevel(level);
    }

    @Override
    public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel level) {
        return delegate.isNotificationForLevelAllowed(level);
    }

    @Override
    public Mono<Void> delete() {
        return Mono.defer(() -> {
            cancelAll();
            return delegate.delete();
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            cancelAll();
            return delegate.closeGracefully();
        });
    }

    @Override
    public void close() {
        cancelAll();
        delegate.close();
    }
}
