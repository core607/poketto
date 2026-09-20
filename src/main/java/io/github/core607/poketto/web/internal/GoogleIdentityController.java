package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.EmailAccounts;
import io.github.core607.poketto.auth.GoogleAccounts;
import io.github.core607.poketto.auth.GoogleIdentityProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/auth/identity/google")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class GoogleIdentityController {
    private static final String FLOW = GoogleIdentityController.class.getName() + ".flow";
    private final GoogleIdentityProvider provider;
    private final GoogleAccounts accounts;
    private final AuthService auth;

    GoogleIdentityController(GoogleIdentityProvider provider, GoogleAccounts accounts, AuthService auth) {
        this.provider = provider;
        this.accounts = accounts;
        this.auth = auth;
    }

    @PostMapping("/start")
    Destination start(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestBody Start input, HttpServletRequest request) {
        if (input.mode() == Mode.LINK) {
            auth.validateAccount(actor);
        } else if (actor != null) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        GoogleIdentityProvider.Authorization authorization = provider.begin();
        request.getSession(true)
                .setAttribute(
                        FLOW,
                        new Pending(
                                authorization,
                                input.mode(),
                                actor,
                                input.returnTo(),
                                Instant.now().plusSeconds(600)));
        return new Destination(authorization.url());
    }

    @GetMapping("/callback")
    void callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response) {
        Pending pending = consume(request, state);
        if (pending == null) {
            redirect(response, "/admin", "google_failed");
            return;
        }
        if (error != null) {
            redirect(response, pending.returnTo(), "google_cancelled");
            return;
        }
        try {
            requireActor(pending, request);
            GoogleAccounts.Identity identity = provider.exchange(pending.authorization(), code);
            requireActor(pending, request);
            AuthPrincipal principal = pending.mode() == Mode.LOGIN
                    ? accounts.authenticate(identity)
                    : accounts.link(pending.actor(), identity);
            establishSession(principal, request, response);
            redirect(response, pending.returnTo(), null);
        } catch (AuthException rejected) {
            String reason =
                    rejected.code() == AuthException.Code.EMAIL_IN_USE ? "google_email_in_use" : "google_failed";
            redirect(response, pending.returnTo(), reason);
        }
    }

    @DeleteMapping
    EmailAccounts.Profile unlink(@AuthenticationPrincipal AuthPrincipal actor) {
        return accounts.unlink(actor);
    }

    private void requireActor(Pending pending, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
        }
        Object saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var authentication = saved instanceof SecurityContext context ? context.getAuthentication() : null;
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (pending.mode() == Mode.LINK) {
            if (!(principal instanceof AuthPrincipal current)
                    || !current.accountId().equals(pending.actor().accountId())
                    || current.credentialVersion() != pending.actor().credentialVersion()) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
            auth.validateAccount(pending.actor());
        } else if (principal instanceof AuthPrincipal) {
            throw new AuthException(AuthException.Code.DENIED);
        }
    }

    private static Pending consume(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null || state == null || state.length() > 256) {
            return null;
        }
        synchronized (session) {
            Object value = session.getAttribute(FLOW);
            if (!(value instanceof Pending pending)) {
                return null;
            }
            if (!MessageDigest.isEqual(
                    state.getBytes(StandardCharsets.UTF_8),
                    pending.authorization().state().getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            session.removeAttribute(FLOW);
            return pending.expiresAt().isAfter(Instant.now()) ? pending : null;
        }
    }

    private void establishSession(AuthPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        auth.validateAccount(principal);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT")));
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())
                .onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    }

    private static void redirect(HttpServletResponse response, String target, String error) {
        var location = UriComponentsBuilder.fromUriString(target).replaceQueryParam("loginError");
        if (error != null) {
            location.queryParam("loginError", error);
        }
        response.setStatus(303);
        response.setHeader("Location", location.build(true).toUriString());
    }

    enum Mode {
        LOGIN,
        LINK
    }

    record Start(Mode mode, String returnTo) {
        Start {
            if (mode == null || returnTo == null || returnTo.length() > 4096) {
                throw new IllegalArgumentException("Google login requires a mode and a bounded local destination");
            }
            URI uri = URI.create(returnTo);
            if (uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getRawFragment() != null
                    || !List.of("/admin", "/connect").contains(uri.getRawPath())) {
                throw new IllegalArgumentException("Google login may return only to the account or connection page");
            }
        }
    }

    record Destination(String url) {
        @Override
        public String toString() {
            return "GoogleDestination[REDACTED]";
        }
    }

    private record Pending(
            GoogleIdentityProvider.Authorization authorization,
            Mode mode,
            AuthPrincipal actor,
            String returnTo,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "GooglePending[REDACTED]";
        }
    }
}
