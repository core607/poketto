package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import jakarta.servlet.AsyncEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ImageMemoryFilterTests {
    @Test
    void exactDownloadsAndUploadReserveBeforeDispatchAndRejectWithoutReading() throws Exception {
        var budget = budget();
        var filter = new ImageMemoryFilter(budget);
        for (String path : List.of("/api/public/assets/token", "/api/admin/assets/images/token", "/api/admin/assets")) {
            for (String method : path.equals("/api/admin/assets") ? List.of("POST") : List.of("GET", "HEAD")) {
                filter.doFilter(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), (in, out) -> {
                    assertThat(budget.reservedBytes()).isEqualTo(ImageMemoryAdmission.BROWSER_BYTES);
                });
                assertThat(budget.reservedBytes()).isZero();
                var held = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
                try {
                    var response = new MockHttpServletResponse();
                    filter.doFilter(
                            new MockHttpServletRequest(method, path), response, (in, out) -> fail("read reached"));
                    assertThat(response.getStatus()).isEqualTo(429);
                    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                    assertThat(response.getHeader("Retry-After")).isEqualTo("2");
                } finally {
                    held.responseComplete();
                }
            }
        }
    }

    @Test
    void saturatedDownloadsDoNotRejectArticlesPreviewsInventoryOrControlEndpoints() throws Exception {
        var budget = budget();
        var filter = new ImageMemoryFilter(budget);
        var held = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        try {
            for (String path : List.of(
                    "/api/public/document",
                    "/api/admin/repository/preview",
                    "/api/admin/assets/repository",
                    "/api/admin/assets",
                    "/api/auth/logout")) {
                var response = new MockHttpServletResponse();
                filter.doFilter(
                        new MockHttpServletRequest("GET", path),
                        response,
                        (in, out) -> out.getWriter().write("body"));
                assertThat(response.getContentAsString()).isEqualTo("body");
            }
            filter.doFilter(
                    new MockHttpServletRequest("POST", "/api/admin/repository/preview"),
                    new MockHttpServletResponse(),
                    (in, out) -> {});
        } finally {
            held.responseComplete();
        }
    }

    @Test
    void timeoutAndErrorKeepAsyncResponseReservationUntilCompletionAndIoFailureReleases() throws Exception {
        var budget = budget();
        var filter = new ImageMemoryFilter(budget);
        var request = new MockHttpServletRequest("GET", "/api/public/assets/token");
        request.setAsyncSupported(true);
        filter.doFilter(request, new MockHttpServletResponse(), (in, out) -> in.startAsync());
        var async = (MockAsyncContext) request.getAsyncContext();
        for (var listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
            listener.onError(new AsyncEvent(async, new IOException("disconnected")));
        }
        assertThat(budget.reservedBytes()).isEqualTo(ImageMemoryAdmission.BROWSER_BYTES);
        async.complete();
        assertThat(budget.reservedBytes()).isZero();
        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/public/assets/token"),
                        new MockHttpServletResponse(),
                        (in, out) -> {
                            throw new IOException("write failed");
                        }))
                .isInstanceOf(IOException.class);
        assertThat(budget.reservedBytes()).isZero();
    }

    private static ImageMemoryAdmission budget() {
        return new ImageMemoryAdmission(ImageMemoryAdmission.BROWSER_BYTES, 1, Duration.ZERO);
    }
}
