package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Holds original-image download and upload reservations through the browser response lifecycle. */
final class ImageMemoryFilter extends OncePerRequestFilter {
    private final ImageMemoryAdmission admission;

    ImageMemoryFilter(ImageMemoryAdmission admission) {
        this.admission = admission;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!imageWork(request)) {
            chain.doFilter(request, response);
            return;
        }
        var reserved = admission.acquire(ImageMemoryAdmission.BROWSER_BYTES);
        if (reserved.isEmpty()) {
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", "2");
            response.getWriter()
                    .write("{\"type\":\"about:blank\",\"title\":\"Image capacity unavailable\",\"status\":429}");
            return;
        }
        ImageRequestScope scope = reserved.orElseThrow();
        try (var producer = scope.producer()) {
            chain.doFilter(request, response);
        } finally {
            completeWithResponse(request, scope);
        }
    }

    private static boolean imageWork(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty())
            path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (path) {
            case "/api/admin/assets" -> request.getMethod().equals("POST");
            default ->
                (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
                        && (path.startsWith("/api/public/assets/") || path.startsWith("/api/admin/assets/images/"));
        };
    }

    private static void completeWithResponse(HttpServletRequest request, ImageRequestScope scope) {
        if (!request.isAsyncStarted()) {
            scope.responseComplete();
            return;
        }
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    scope.responseComplete();
                }

                @Override
                public void onTimeout(AsyncEvent event) {}

                @Override
                public void onError(AsyncEvent event) {}

                @Override
                public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this);
                }
            });
        } catch (IllegalStateException completed) {
            scope.responseComplete();
        }
    }
}
