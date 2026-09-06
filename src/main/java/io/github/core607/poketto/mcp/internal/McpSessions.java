package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Binds real SDK-created sessions to authenticated keys; caller headers cannot create a binding. */
final class McpSessions implements AutoCloseable {
    static final String IDENTITY_CONTEXT = "poketto.mcp.identity";
    private static final Logger log = LoggerFactory.getLogger(McpSessions.class);
    private final AuthService auth;
    private final WorkspaceCatalog workspaces;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration idleTimeout;
    private final int maxSessions;
    private final Map<String, Binding> bindings = new HashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("poketto-mcp-session-reaper").factory());
    private boolean closed;

    McpSessions(
            AuthService auth,
            WorkspaceCatalog workspaces,
            ApplicationEventPublisher events,
            Clock clock,
            Duration idleTimeout,
            int maxSessions) {
        if (idleTimeout.isNegative() || idleTimeout.isZero() || maxSessions < 1 || maxSessions > 1024)
            throw new IllegalArgumentException("invalid MCP session bounds");
        this.auth = auth;
        this.workspaces = workspaces;
        this.events = events;
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.maxSessions = maxSessions;
        reaper.scheduleWithFixedDelay(
                this::expire, 1, Math.min(30, Math.max(1, idleTimeout.toSeconds())), TimeUnit.SECONDS);
    }

    Identity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || principal.kind() != AuthPrincipal.Kind.API_KEY)
            throw new SecurityException("MCP authentication required");
        WorkspaceId workspace = workspaces.defaultWorkspace().id();
        auth.authorize(principal, workspace);
        return new Identity(principal, workspace);
    }

    synchronized void bind(McpStreamableServerSession session, Identity identity) {
        if (closed || bindings.size() >= maxSessions) {
            session.closeGracefully().onErrorComplete().subscribe();
            throw new IllegalStateException("MCP session capacity unavailable");
        }
        bindings.put(session.getId(), new Binding(session, identity, clock.instant()));
    }

    synchronized void check(String sessionId, Identity identity) {
        Binding binding = bindings.get(sessionId);
        if (binding == null
                || closed
                || !binding.identity.workspace().equals(identity.workspace())
                || !binding.identity
                        .principal()
                        .subjectId()
                        .equals(identity.principal().subjectId())
                || !clock.instant().isBefore(binding.lastAccess.plus(idleTimeout))) {
            throw new SecurityException("MCP session unavailable");
        }
        binding.lastAccess = clock.instant();
    }

    Identity resolve(McpSyncServerExchange exchange) {
        if (!(exchange.transportContext().get(IDENTITY_CONTEXT) instanceof Identity identity))
            throw new SecurityException("MCP request identity unavailable");
        auth.authorize(identity.principal(), identity.workspace());
        check(exchange.sessionId(), identity);
        return identity;
    }

    void remove(String id, McpSessionClosed.Reason reason) {
        Binding removed;
        synchronized (this) {
            Binding current = bindings.get(id);
            if (reason == McpSessionClosed.Reason.IDLE_EXPIRY
                    && current != null
                    && clock.instant().isBefore(current.lastAccess.plus(idleTimeout))) return;
            removed = bindings.remove(id);
        }
        if (removed == null) return;
        try {
            events.publishEvent(new McpSessionClosed(
                    removed.identity.workspace(), removed.identity.principal().subjectId(), id, reason));
        } catch (RuntimeException exception) {
            log.warn("MCP session cleanup listener failed; execution leases must also enforce expiry");
        } finally {
            removed.session.closeGracefully().onErrorComplete().subscribe();
        }
    }

    @EventListener
    void revoke(AuthRevocation event) {
        var ids = new ArrayList<String>();
        synchronized (this) {
            bindings.forEach((id, binding) -> {
                Identity identity = binding.identity;
                if (event.workspaceId().equals(identity.workspace())
                        && (event.apiKeyIds().contains(identity.principal().subjectId())
                                || event.accountIds()
                                        .contains(identity.principal().accountId()))) ids.add(id);
            });
        }
        ids.forEach(id -> remove(id, McpSessionClosed.Reason.AUTH_REVOKED));
    }

    private void expire() {
        var ids = new ArrayList<String>();
        synchronized (this) {
            bindings.forEach((id, binding) -> {
                if (!clock.instant().isBefore(binding.lastAccess.plus(idleTimeout))) ids.add(id);
            });
        }
        ids.forEach(id -> remove(id, McpSessionClosed.Reason.IDLE_EXPIRY));
    }

    @Override
    public void close() {
        ArrayList<String> ids;
        synchronized (this) {
            closed = true;
            ids = new ArrayList<>(bindings.keySet());
        }
        reaper.shutdownNow();
        ids.forEach(id -> remove(id, McpSessionClosed.Reason.SHUTDOWN));
    }

    record Identity(AuthPrincipal principal, WorkspaceId workspace) {}

    private static final class Binding {
        private final McpStreamableServerSession session;
        private final Identity identity;
        private Instant lastAccess;

        private Binding(McpStreamableServerSession session, Identity identity, Instant lastAccess) {
            this.session = session;
            this.identity = identity;
            this.lastAccess = lastAccess;
        }
    }
}
