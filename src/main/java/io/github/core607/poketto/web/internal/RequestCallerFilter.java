package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the authenticated caller onto the request so the completion record can name it.
 *
 * <p>{@link RequestDiagnosticsFilter} is registered outside the security chain so that a request
 * refused before a controller is still recorded. By the time its record is written the security
 * chain has cleared its context, so reading the caller there would report every request as
 * anonymous. This filter is registered inside that chain instead, where the context still holds
 * the identity, and leaves it on the request, which survives an asynchronous dispatch.
 *
 * <p>Only the kind and subject identifier are carried. An account name is not, and a credential
 * never is.
 */
final class RequestCallerFilter extends OncePerRequestFilter {

    static final String CALLER = RequestCallerFilter.class.getName() + ".caller";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // The identity is captured on the initial dispatch and stays on the request afterwards.
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        capture(request);
        try {
            chain.doFilter(request, response);
        } finally {
            // A chain filter can authenticate after this one runs, so take the later identity too.
            capture(request);
        }
    }

    private static void capture(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal) {
            request.setAttribute(CALLER, principal.toString());
        }
    }
}
