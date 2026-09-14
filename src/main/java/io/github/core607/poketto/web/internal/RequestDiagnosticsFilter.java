package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.RequestCaller;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** An image grant is 32 random bytes as base64url, so anything this long is treated as one. */
    private static final Pattern OPAQUE_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{24,}");

    /** A public site slug follows this segment and names the space, so it is kept as authored. */
    private static final String SLUG_PARENT = "spaces";

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
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    record(request, response, id, started);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    // Completion still follows a timeout, so the record is written from onComplete.
                }

                @Override
                public void onError(AsyncEvent event) {
                    // Completion still follows an error, so the record is written from onComplete.
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this);
                }
            });
        } catch (IllegalStateException completed) {
            // The dispatch finished before the listener could attach; record it here instead of
            // letting a diagnostic throw out of a request that otherwise succeeded.
            record(request, response, id, started);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, String id, long started) {
        long milliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int status = response.getStatus();
        String method = request.getMethod();
        String route = route(request);
        String caller = caller(request);
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

    /**
     * Reduces the request to a stable route: no query string, no workspace identifier, and no
     * opaque segment.
     *
     * <p>Collapsing by shape rather than by a list of known routes matters because an image URL
     * carries its authorization in the path. That token is a bearer capability for the exact
     * image until it expires, so a recorded route containing one hands anyone who can read the
     * log the image it names. A shape rule also covers a route added later without this filter
     * being revisited.
     *
     * <p>What is kept is named explicitly instead, because failing to keep a readable segment
     * costs legibility while failing to collapse an opaque one leaks a capability. A public site
     * slug is the one such segment today: it is authored, already public, and distinguishes one
     * space from another in a record.
     */
    private static String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return withoutOpaqueSegments(adminRoute(path));
    }

    private static String adminRoute(String path) {
        if (!path.startsWith(WorkspaceHttpRoutes.ADMIN)) {
            return path;
        }
        try {
            return WorkspaceHttpRoutes.operation(path);
        } catch (IllegalArgumentException malformed) {
            return WorkspaceHttpRoutes.ADMIN;
        }
    }

    private static String withoutOpaqueSegments(String path) {
        String[] segments = path.split("/", -1);
        for (int index = segments.length - 1; index > 0; index--) {
            if (!SLUG_PARENT.equals(segments[index - 1])) {
                segments[index] = placeholder(segments[index]);
            }
        }
        return String.join("/", segments);
    }

    private static String placeholder(String segment) {
        if (UUID_SEGMENT.matcher(segment).matches()) {
            return ":id";
        }
        if (OPAQUE_SEGMENT.matcher(segment).matches()) {
            return ":opaque";
        }
        return segment;
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

    /**
     * Names the authenticated kind and subject, never an account name or a credential. The
     * security chain has already cleared its context by the time a record is written, and a
     * request refused during authorization never reaches the end of that chain, so the identity is
     * remembered on the request when it is recognised.
     */
    private static String caller(HttpServletRequest request) {
        return RequestCaller.of(request);
    }

    /** Container probes run continuously and report nothing a reader would act on. */
    private static boolean health(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/actuator");
    }
}
