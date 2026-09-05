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
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Limits the real request stream, including chunked bodies, before the SDK materializes JSON. */
final class McpBodyLimitFilter implements Filter {
    static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;
    static final int MAX_INITIALIZE_BYTES = 16 * 1024;
    private final Semaphore activePosts = new Semaphore(4);

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
        if (!activePosts.tryAcquire()) {
            reject(output, 429);
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) activePosts.release();
        };
        try {
            chain.doFilter(
                    new HttpServletRequestWrapper(http) {
                        private ServletInputStream bounded;

                        @Override
                        public ServletInputStream getInputStream() throws IOException {
                            if (bounded != null) return bounded;
                            ServletInputStream source = super.getInputStream();
                            bounded = new ServletInputStream() {
                                private int count;

                                @Override
                                public int read() throws IOException {
                                    int value = source.read();
                                    if (value >= 0 && ++count > limit)
                                        throw new IOException("MCP request exceeds its byte limit");
                                    return value;
                                }

                                @Override
                                public int read(byte[] buffer, int offset, int length) throws IOException {
                                    int read = source.read(buffer, offset, Math.min(length, limit - count + 1));
                                    if (read > 0 && (count += read) > limit)
                                        throw new IOException("MCP request exceeds its byte limit");
                                    return read;
                                }

                                @Override
                                public boolean isFinished() {
                                    return source.isFinished();
                                }

                                @Override
                                public boolean isReady() {
                                    return source.isReady();
                                }

                                @Override
                                public void setReadListener(ReadListener listener) {
                                    source.setReadListener(listener);
                                }
                            };
                            return bounded;
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

    private static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"MCP request rejected\",\"status\":" + status + "}");
    }
}
