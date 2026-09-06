package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/** Bounds request streams and reserves separate admission for small SDK lifecycle notifications. */
final class McpBodyLimitFilter implements Filter {
    static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;
    static final int MAX_INITIALIZE_BYTES = 16 * 1024;
    private final Semaphore activePosts = new Semaphore(4);
    private final Semaphore activeControls = new Semaphore(4);
    private final Semaphore readingPrefixes = new Semaphore(8);
    private final ObjectMapper json;
    private final ImageMemoryAdmission memory;

    McpBodyLimitFilter(ObjectMapper json, ImageMemoryAdmission memory) {
        this.json = json;
        this.memory = memory;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse output = (HttpServletResponse) response;
        if (!http.getMethod().equals("POST")) {
            chain.doFilter(request, response);
            return;
        }
        int limit = http.getHeader("Mcp-Session-Id") == null ? MAX_INITIALIZE_BYTES : MAX_REQUEST_BYTES;
        if (http.getContentLengthLong() > limit) {
            reject(output, 413);
            return;
        }
        if (!readingPrefixes.tryAcquire()) {
            reject(output, 429);
            return;
        }
        ServletInputStream original;
        byte[] prefix;
        try {
            original = http.getInputStream();
            prefix = original.readNBytes(MAX_INITIALIZE_BYTES + 1);
        } finally {
            readingPrefixes.release();
        }
        if (prefix.length > limit) {
            reject(output, 413);
            return;
        }
        Semaphore admission =
                http.getHeader("Mcp-Session-Id") != null && control(prefix) ? activeControls : activePosts;
        if (!admission.tryAcquire()) {
            reject(output, 429);
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) admission.release();
        };
        boolean imageWork = imageWork(prefix);
        var reservation = imageWork
                ? memory.acquire(ImageMemoryAdmission.MCP_BYTES)
                : java.util.Optional.<ImageRequestScope>empty();
        if (imageWork && reservation.isEmpty()) {
            release.run();
            reject(output, 429);
            return;
        }
        ImageRequestScope scope = reservation.orElse(null);
        if (scope != null) http.setAttribute(ImageRequestScope.ATTRIBUTE, scope);
        Runnable complete = () -> {
            if (scope != null) scope.responseComplete();
            release.run();
        };
        AsyncListener listener = new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                complete.run();
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                finishAsync(event, output, 503);
            }

            @Override
            public void onError(AsyncEvent event) {
                finishAsync(event, output, 500);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                event.getAsyncContext().addListener(this);
            }
        };
        AtomicBoolean watching = new AtomicBoolean();
        try (var producer = scope == null ? (ImageRequestScope.Producer) () -> {} : scope.producer()) {
            // The SDK consumes stream exceptions itself, so enforce the bound before dispatch.
            byte[] body = prefix;
            int size = prefix.length;
            if (prefix.length > MAX_INITIALIZE_BYTES) {
                body = new byte[limit + 1];
                System.arraycopy(prefix, 0, body, 0, prefix.length);
                size += original.readNBytes(body, size, body.length - size);
            }
            if (size > limit) {
                reject(output, 413);
                return;
            }
            var envelope = new McpEnvelopeBounds(json).inspect(body, size);
            if (envelope == McpEnvelopeBounds.Result.TOO_COMPLEX) {
                reject(output, 413);
                return;
            }
            if (envelope == McpEnvelopeBounds.Result.INVALID_ID || envelope == McpEnvelopeBounds.Result.INVALID_JSON) {
                output.setStatus(400);
                output.setContentType("application/json");
                output.setHeader("Cache-Control", "no-store");
                String message = envelope == McpEnvelopeBounds.Result.INVALID_ID
                        ? "Invalid request identifier"
                        : "Invalid message format";
                output.getWriter()
                        .write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"" + message + "\"}}");
                return;
            }
            var source = new ByteArrayInputStream(body, 0, size);
            chain.doFilter(
                    new HttpServletRequestWrapper(http) {
                        private ServletInputStream bounded;

                        @Override
                        public AsyncContext startAsync() {
                            return watch(super.startAsync());
                        }

                        @Override
                        public AsyncContext startAsync(ServletRequest input, ServletResponse output) {
                            return watch(super.startAsync(input, output));
                        }

                        private AsyncContext watch(AsyncContext context) {
                            // Register before the SDK's synchronous SSE producer can fail its first write.
                            context.addListener(listener);
                            watching.set(true);
                            return context;
                        }

                        @Override
                        public ServletInputStream getInputStream() throws IOException {
                            if (bounded != null) return bounded;
                            bounded = new ServletInputStream() {
                                @Override
                                public int read() {
                                    return source.read();
                                }

                                @Override
                                public int read(byte[] buffer, int offset, int length) {
                                    return source.read(buffer, offset, length);
                                }

                                @Override
                                public boolean isFinished() {
                                    return source.available() == 0;
                                }

                                @Override
                                public boolean isReady() {
                                    return true;
                                }

                                @Override
                                public void setReadListener(ReadListener listener) {
                                    throw new IllegalStateException("MCP request bodies use blocking reads");
                                }
                            };
                            return bounded;
                        }

                        @Override
                        public BufferedReader getReader() throws IOException {
                            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                        }
                    },
                    response);
        } finally {
            if (http.isAsyncStarted()) {
                try {
                    if (!watching.get()) http.getAsyncContext().addListener(listener);
                } catch (IllegalStateException exception) {
                    complete.run();
                }
            } else complete.run();
        }
    }

    private static void finishAsync(AsyncEvent event, HttpServletResponse output, int status) {
        try {
            if (!output.isCommitted()) {
                output.setStatus(status);
                output.setContentType("application/problem+json");
                output.setHeader("Cache-Control", "no-store");
                output.getOutputStream()
                        .write(("{\"type\":\"about:blank\",\"title\":\"MCP response unavailable\",\"status\":" + status
                                        + "}")
                                .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | IllegalStateException disconnected) {
            // The stream may already have failed; the servlet still needs a terminal lifecycle.
        }
        try {
            // Spring's SSE builder cannot finish itself after sendFailed; close the servlet lifecycle.
            event.getAsyncContext().complete();
        } catch (IllegalStateException completed) {
            // A competing completion owns onComplete. Producer reservations remain independent.
        }
    }

    private boolean imageWork(byte[] prefix) {
        // A large request may put the tool name after its arguments. Reserve before buffering it.
        if (prefix.length > MAX_INITIALIZE_BYTES) return true;
        try {
            var message = json.readTree(prefix);
            if (!message.path("method").asString("").equals("tools/call")) return false;
            String tool = message.path("params").path("name").asString("");
            return tool.equals("get_asset") || tool.equals("put_asset");
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean control(byte[] body) {
        if (body.length > MAX_INITIALIZE_BYTES) return false;
        try {
            var message = json.readTree(body);
            String method = message.path("method").asString("");
            return !message.has("id")
                    && message.path("jsonrpc").asString("").equals("2.0")
                    && (method.equals("notifications/cancelled") || method.equals("notifications/initialized"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"MCP request rejected\",\"status\":" + status + "}");
    }
}
