package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after current membership validation, before CSRF parameter parsing or MVC body reads. */
final class AdminBodyFilter extends OncePerRequestFilter {
    private final Semaphore activeBodies;

    AdminBodyFilter(int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("admin body concurrency must be positive");
        }
        activeBodies = new Semaphore(concurrency);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = WorkspaceHttpRoutes.operation(AuthHttpErrors.path(request));
        if (!path.startsWith("/api/admin/")) {
            chain.doFilter(request, response);
            return;
        }
        int limit = OriginAndBodyFilter.bodyLimit(path);
        if (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) {
            // Download lifetime must not occupy the separate upload/request-body reservation.
            OriginAndBodyFilter.filterBody(request, response, chain, OriginAndBodyFilter.MAX_AUTH_BODY);
            return;
        }
        if (limit == OriginAndBodyFilter.MAX_AUTH_BODY) {
            OriginAndBodyFilter.filterBody(request, response, chain, limit);
            return;
        }
        if (request.getMethod().equals("POST") && !supportedType(path, request.getContentType())) {
            AuthHttpErrors.write(response, 415);
            return;
        }
        if (!activeBodies.tryAcquire()) {
            AuthHttpErrors.write(response, 429);
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                activeBodies.release();
            }
        };
        try {
            OriginAndBodyFilter.filterBody(request, response, chain, limit);
        } finally {
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        @Override
                        public void onComplete(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) {
                            // A timeout callback does not end asynchronous request processing.
                        }

                        @Override
                        public void onError(AsyncEvent event) {
                            // The container completes or dispatches the error before onComplete.
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) {
                            event.getAsyncContext().addListener(this);
                        }
                    });
                } catch (IllegalStateException exception) {
                    // Completion raced with listener registration.
                    release.run();
                }
            } else {
                release.run();
            }
        }
    }

    private static boolean supportedType(String path, String supplied) {
        if (supplied == null) {
            return false;
        }
        try {
            var type = MediaType.parseMediaType(supplied);
            if (path.equals("/api/admin/media")) {
                return MediaType.APPLICATION_OCTET_STREAM.equals(type);
            }
            return path.equals("/api/admin/assets")
                    ? MediaType.MULTIPART_FORM_DATA.includes(type)
                    : MediaType.APPLICATION_JSON.includes(type)
                            || (type.getType().equalsIgnoreCase("application")
                                    && type.getSubtype()
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith("+json"));
        } catch (InvalidMediaTypeException exception) {
            return false;
        }
    }
}
