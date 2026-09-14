package io.github.core607.poketto.auth;

import jakarta.servlet.ServletRequest;
import java.util.Optional;

/**
 * Carries the recognised caller on the request so a record written after the security chain can
 * still name it.
 *
 * <p>Authorization is decided after authentication, so a request can be refused with its account
 * or key already known. The security chain then clears its context and returns, and a filter
 * outside it sees nothing. Recording "anonymous" for a request that arrived with a valid session
 * or key sends a reader looking for a leaked credential, so the identity is remembered the moment
 * it is recognised rather than read back later.
 *
 * <p>Only the kind and subject identifier are carried. An account name is not, and a credential
 * never is.
 */
public final class RequestCaller {

    private static final String ATTRIBUTE = RequestCaller.class.getName();

    /** Named in a record when no identity was recognised before the response was written. */
    public static final String ANONYMOUS = "anonymous";

    private RequestCaller() {}

    /** Called wherever a principal is first recognised, before any refusal is written. */
    public static void remember(ServletRequest request, AuthPrincipal principal) {
        if (request == null || principal == null) {
            return;
        }
        request.setAttribute(ATTRIBUTE, principal.toString());
    }

    /** The recognised caller, or {@link #ANONYMOUS} when none was reached. */
    public static String of(ServletRequest request) {
        if (request == null) {
            return ANONYMOUS;
        }
        return Optional.ofNullable(request.getAttribute(ATTRIBUTE))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(ANONYMOUS);
    }
}
