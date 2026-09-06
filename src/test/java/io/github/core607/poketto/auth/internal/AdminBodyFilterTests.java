package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminBodyFilterTests {
    @Test
    void saturationRejectsDeclaredChunkedAndMultipartWithoutOpeningTheirStreams() throws Exception {
        var filter = new AdminBodyFilter(1);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> {
                filter.doFilter(request(false, false), new MockHttpServletResponse(), (request, response) -> {
                    entered.countDown();
                    await(finish);
                });
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (boolean declared : new boolean[] {true, false}) {
                    for (boolean multipart : new boolean[] {true, false}) {
                        var request = request(declared, multipart);
                        var opened = new AtomicInteger();
                        request.streams = opened;
                        var response = new MockHttpServletResponse();
                        filter.doFilter(request, response, (req, res) -> fail("saturated request dispatched"));
                        assertThat(response.getStatus()).isEqualTo(429);
                        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                        assertThat(opened).hasValue(0);
                    }
                }
            } finally {
                finish.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
        accepted(filter);
    }

    @Test
    void permitCoversTheFirstReadAndIsReleasedOnClientReadFailure() throws Exception {
        var filter = new AdminBodyFilter(1);
        var reading = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var first = request(false, false);
        first.input = new ServletInputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                await(disconnected);
                throw new IOException("synthetic client disconnect");
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> {
                assertThatThrownBy(() -> filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> fail()))
                        .isInstanceOf(IOException.class);
            });
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                rejected(filter);
            } finally {
                disconnected.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
        accepted(filter);
    }

    @Test
    void downstreamExceptionsAndOverflowReleaseAdmission() throws Exception {
        var filter = new AdminBodyFilter(1);
        for (boolean checked : new boolean[] {true, false}) {
            assertThatThrownBy(() -> filter.doFilter(request(true, true), new MockHttpServletResponse(), (req, res) -> {
                        if (checked) throw new ServletException("failed part parsing");
                        throw new IllegalStateException("failed controller");
                    }))
                    .isInstanceOf(checked ? ServletException.class : IllegalStateException.class);
            accepted(filter);
        }
        for (int size : new int[] {6 * 1024 * 1024 + 1, 6 * 1024 * 1024}) {
            var request = request(false, false);
            request.setContent(new byte[size]);
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> {
                assertThat(req.getInputStream().readAllBytes()).hasSize(size);
                called.set(true);
            });
            assertThat(response.getStatus()).isEqualTo(size > 6 * 1024 * 1024 ? 413 : 200);
            assertThat(called.get()).isEqualTo(size == 6 * 1024 * 1024);
            accepted(filter);
        }
    }

    @Test
    void asynchronousTimeoutAndErrorKeepAdmissionUntilCompletion() throws Exception {
        var filter = new AdminBodyFilter(1);
        var request = request(true, false);
        request.setAsyncSupported(true);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.startAsync(req, res));
        var context = (MockAsyncContext) request.getAsyncContext();
        assertThat(context.getListeners()).hasSize(1);
        var listener = context.getListeners().getFirst();
        rejected(filter);
        listener.onTimeout(new AsyncEvent(context));
        rejected(filter);
        listener.onError(new AsyncEvent(context));
        rejected(filter);
        var next = new MockAsyncContext(request, new MockHttpServletResponse());
        listener.onStartAsync(new AsyncEvent(next));
        assertThat(next.getListeners()).containsExactly(listener);
        listener.onComplete(new AsyncEvent(next));
        listener.onComplete(new AsyncEvent(next));
        filter.doFilter(request(true, false), new MockHttpServletResponse(), (req, res) -> rejected(filter));
        accepted(filter);
    }

    @Test
    void unsupportedLargeFormIsRejectedWithoutDecodingOrConsumingIt() throws Exception {
        var filter = new AdminBodyFilter(1);
        for (String path :
                new String[] {"/api/admin/assets", "/api/admin/repository/patch", "/api/admin/repository/preview"}) {
            var request = request(false, false);
            request.setRequestURI(path);
            request.setContentType("application/x-www-form-urlencoded");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("unsupported body dispatched"));
            assertThat(response.getStatus()).isEqualTo(415);
            assertThat(request.streams).hasValue(0);
        }
    }

    private static void rejected(AdminBodyFilter filter) throws IOException, ServletException {
        var request = request(false, false);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("saturated request dispatched"));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(request.streams).hasValue(0);
    }

    private static void accepted(AdminBodyFilter filter) throws Exception {
        var called = new AtomicBoolean();
        filter.doFilter(request(false, false), new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(called).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static CountingRequest request(boolean declared, boolean multipart) {
        var request = new CountingRequest(declared);
        request.setMethod("POST");
        request.setRequestURI(multipart ? "/api/admin/assets" : "/api/admin/repository/preview");
        request.setContentType(multipart ? "multipart/form-data; boundary=x" : "application/json");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static class CountingRequest extends MockHttpServletRequest {
        final boolean declared;
        AtomicInteger streams = new AtomicInteger();
        ServletInputStream input;

        CountingRequest(boolean declared) {
            this.declared = declared;
        }

        @Override
        public int getContentLength() {
            return declared ? super.getContentLength() : -1;
        }

        @Override
        public long getContentLengthLong() {
            return getContentLength();
        }

        @Override
        public ServletInputStream getInputStream() {
            streams.incrementAndGet();
            return input == null ? super.getInputStream() : input;
        }
    }
}
