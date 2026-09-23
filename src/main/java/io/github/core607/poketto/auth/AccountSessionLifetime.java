package io.github.core607.poketto.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

/**
 * How long a browser session that carries a signed-in account may stay idle. Every route that
 * stores an account in a session applies it at that moment; anonymous sessions keep the shorter
 * servlet default.
 */
public final class AccountSessionLifetime {
    private final int seconds;

    public AccountSessionLifetime(Duration idle) {
        if (idle.isNegative() || idle.isZero()) {
            throw new IllegalArgumentException("account session idle time must be positive");
        }
        this.seconds = Math.toIntExact(idle.toSeconds());
    }

    /** Gives the request's current session the account idle timeout; a request without one is left alone. */
    public void apply(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getMaxInactiveInterval() != seconds) {
            session.setMaxInactiveInterval(seconds);
        }
    }
}
