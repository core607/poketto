package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request one identifier and one completion record.
 *
 * <p>The identifier stays server-side. It joins the several log lines one request can produce; it
 * is never returned to a caller, because a client that cannot redeem an identifier gains nothing
 * from holding one and an unexplained token invites a model to invent a use for it. Correlation
 * with a reported failure therefore uses what both sides already hold: the workspace, the account
 * or key, and the time.
 *
 * <p>The record names the route, not the request line. A query string carries repository paths and
 * an admin route carries its workspace identifier, so the route is reduced to its stable shape
 * before it reaches a log. Request bodies are never recorded: repository tokens and passwords
 * arrive in them.
 *
 * <p>An asynchronous request completes on another thread, so the record is written from a
 * completion listener with values captured here rather than from thread-local context.
 */
final class RequestDiagnosticsFilter extends OncePerRequestFilter {

    /** Read by other logging on the request thread; absent on asynchronous dispatch threads. */
    static final String REQUEST_ID = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestDiagnosticsFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (health(request)) {
            chain.doFilter(request, response);
            return;
        }
        String id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        MDC.put(REQUEST_ID, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
            completeWhenFinished(request, response, id, started);
        }
    }

    private void completeWhenFinished(
            HttpServletRequest request, HttpServletResponse response, String id, long started) {
        if (!request.isAsyncStarted()) {
            record(request, response, id, started);
            return;
        }
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                record(request, response, id, started);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                // Completion still follows a timeout, so the record is written once, from onComplete.
            }

            @Override
            public void onError(AsyncEvent event) {
                // Completion still follows an error, so the record is written once, from onComplete.
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                event.getAsyncContext().addListener(this);
            }
        });
    }

    private void record(HttpServletRequest request, HttpServletResponse response, String id, long started) {
        long milliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int status = response.getStatus();
        String method = request.getMethod();
        String route = route(request);
        String caller = caller();
        String workspace = workspace(request);
        var entry = status >= 500 ? log.atWarn() : log.atInfo();
        // Values appear as key values for JSON records and in the message for the readable format,
        // because the console pattern renders the message alone.
        entry.addKeyValue(REQUEST_ID, id)
                .addKeyValue("method", method)
                .addKeyValue("route", route)
                .addKeyValue("status", status)
                .addKeyValue("durationMs", milliseconds)
                .addKeyValue("caller", caller)
                .addKeyValue("workspace", workspace)
                .setMessage("http request {} {} status {} after {} ms caller {} workspace {} id {}")
                .addArgument(method)
                .addArgument(route)
                .addArgument(status)
                .addArgument(milliseconds)
                .addArgument(caller)
                .addArgument(workspace)
                .addArgument(id)
                .log();
    }

    /** Reduces the request to a stable route: no query string, and no workspace identifier. */
    private static String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (!path.startsWith(WorkspaceHttpRoutes.ADMIN)) {
            return path;
        }
        try {
            return WorkspaceHttpRoutes.operation(path);
        } catch (IllegalArgumentException malformed) {
            return WorkspaceHttpRoutes.ADMIN;
        }
    }

    private static String workspace(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(WorkspaceHttpRoutes.ADMIN)) {
            return "";
        }
        try {
            return WorkspaceHttpRoutes.workspace(path).toString();
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    /** Names the authenticated kind and subject, never an account name or a credential. */
    private static String caller() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.toString();
        }
        return "anonymous";
    }

    /** Container probes run continuously and report nothing a reader would act on. */
    private static boolean health(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/actuator");
    }
}
