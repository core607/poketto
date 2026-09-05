package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.mcp.McpSessionClosed;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.util.List;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import reactor.core.publisher.Mono;

/** Retains the official transport and SDK factory while binding their actual generated session ids. */
final class BoundMcpTransport implements McpStreamableServerTransportProvider {
    private final WebMvcStreamableServerTransportProvider delegate;
    private final McpSessions sessions;

    BoundMcpTransport(WebMvcStreamableServerTransportProvider delegate, McpSessions sessions) {
        this.delegate = delegate;
        this.sessions = sessions;
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        delegate.setSessionFactory(request -> {
            var identity = sessions.currentIdentity();
            var initialized = factory.startSession(request);
            sessions.bind(initialized.session(), identity);
            return new McpStreamableServerSession.McpStreamableServerSessionInit(
                    initialized.session(),
                    initialized
                            .initResult()
                            .doOnError(error -> sessions.remove(
                                    initialized.session().getId(), McpSessionClosed.Reason.INITIALIZATION_FAILED)));
        });
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            sessions.close();
            return delegate.closeGracefully();
        });
    }
}
