package io.github.core607.poketto.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class OriginAndBodyFilter extends OncePerRequestFilter {
    static final int MAX_AUTH_BODY = 16 * 1024;
    private final Set<String> origins;

    OriginAndBodyFilter(Set<String> origins) {
        this.origins = Set.copyOf(origins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var supplied = Collections.list(request.getHeaders("Origin"));
        if (!supplied.isEmpty() && (supplied.size() != 1 || !origins.contains(supplied.getFirst()))) {
            AuthHttpErrors.write(response, 403);
            return;
        }
        String path = request.getServletPath();
        if (path.isEmpty())
            path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/auth/") || path.startsWith("/api/admin/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            if (request.getContentLengthLong() > MAX_AUTH_BODY) {
                AuthHttpErrors.write(response, 413);
                return;
            }
            chain.doFilter(
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public ServletInputStream getInputStream() throws IOException {
                            ServletInputStream input = super.getInputStream();
                            return new ServletInputStream() {
                                private int bytes;

                                @Override
                                public int read() throws IOException {
                                    int value = input.read();
                                    if (value >= 0 && ++bytes > MAX_AUTH_BODY)
                                        throw new IOException("authentication request exceeds its byte limit");
                                    return value;
                                }

                                @Override
                                public boolean isFinished() {
                                    return input.isFinished();
                                }

                                @Override
                                public boolean isReady() {
                                    return input.isReady();
                                }

                                @Override
                                public void setReadListener(ReadListener listener) {
                                    input.setReadListener(listener);
                                }
                            };
                        }
                    },
                    response);
            return;
        }
        chain.doFilter(request, response);
    }
}
