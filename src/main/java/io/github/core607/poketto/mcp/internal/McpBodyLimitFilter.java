package io.github.core607.poketto.mcp.internal;

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

    McpBodyLimitFilter(ObjectMapper json) {
        this.json = json;
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
        try {
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
            var source = new ByteArrayInputStream(body, 0, size);
            chain.doFilter(
                    new HttpServletRequestWrapper(http) {
                        private ServletInputStream bounded;

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
                    http.getAsyncContext().addListener(new AsyncListener() {
                        @Override
                        public void onComplete(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onError(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) {
                            event.getAsyncContext().addListener(this);
                        }
                    });
                } catch (IllegalStateException exception) {
                    release.run();
                }
            } else release.run();
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
