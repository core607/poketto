package io.github.core607.poketto.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded fixed windows. Unknown source addresses share a bucket; forwarded headers are not trusted here. */
final class LoginThrottleFilter extends OncePerRequestFilter {
    private final Clock clock;
    private final int perAccount;
    private final int perAddress;
    private final int maxEntries;
    private final Duration window;
    private final Map<String, Attempts> attempts = new HashMap<>();

    LoginThrottleFilter(Clock clock, int perAccount, int perAddress, int maxEntries, Duration window) {
        if (perAccount < 1 || perAddress < 1 || maxEntries < 2 || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("login throttle limits must be positive");
        this.clock = clock;
        this.perAccount = perAccount;
        this.perAddress = perAddress;
        this.maxEntries = maxEntries;
        this.window = window;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = AuthHttpErrors.path(request);
        if (request.getMethod().equals("POST")
                && (path.equals("/api/auth/login")
                        || path.equals("/api/auth/initialize")
                        || path.equals("/api/auth/invitations/register"))) {
            String login = path.equals("/api/auth/login") ? request.getParameter("username") : null;
            if (!take(request.getRemoteAddr(), login)) {
                response.setHeader("Retry-After", Long.toString(window.toSeconds()));
                AuthHttpErrors.write(response, 429);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    synchronized boolean take(String address, String login) {
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        String ipKey = "ip:" + (address == null || address.length() > 128 ? "unknown" : address);
        String accountKey =
                login == null ? null : "account:" + (login.length() > 64 ? "invalid" : login.toLowerCase(Locale.ROOT));
        if (!canTake(ipKey, perAddress, now) || (accountKey != null && !canTake(accountKey, perAccount, now)))
            return false;
        int additional = (attempts.containsKey(ipKey) ? 0 : 1)
                + (accountKey == null || attempts.containsKey(accountKey) ? 0 : 1);
        if (attempts.size() + additional > maxEntries) return false;
        increment(ipKey, now);
        if (accountKey != null) increment(accountKey, now);
        return true;
    }

    private boolean canTake(String key, int maximum, long now) {
        Attempts entry = attempts.get(key);
        return entry == null || entry.expiresAt() <= now || entry.count() < maximum;
    }

    private void increment(String key, long now) {
        Attempts previous = attempts.get(key);
        attempts.put(
                key,
                previous == null
                        ? new Attempts(1, now + window.toMillis())
                        : new Attempts(previous.count() + 1, previous.expiresAt()));
    }

    private record Attempts(int count, long expiresAt) {}
}
