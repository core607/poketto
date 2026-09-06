package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.ImageRequestScope;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import reactor.core.publisher.Mono;

/** Accounts for the actual blocking WebMVC JSON/SSE write even when a subscriber cancels. */
final class ImageBudgetTransport implements McpStreamableServerTransport {
    private final McpStreamableServerTransport delegate;
    private final ImageRequestScope scope;

    ImageBudgetTransport(McpStreamableServerTransport delegate, ImageRequestScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return send(() -> delegate.sendMessage(message));
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String eventId) {
        return send(() -> delegate.sendMessage(message, eventId));
    }

    private Mono<Void> send(java.util.function.Supplier<Mono<Void>> operation) {
        return Mono.fromRunnable(() -> {
            // The guard is inside the executing operation, never a doFinally cancellation callback.
            try (var producer = scope.producer()) {
                operation.get().block();
            }
        });
    }

    @Override
    public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
        return delegate.unmarshalFrom(value, type);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
