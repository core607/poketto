package io.github.core607.poketto.auth.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.OAuthService;
import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.oauth.issuer")
class OAuthController {
    private static final String PENDING = OAuthController.class.getName() + ".requests";
    private final OAuthService oauth;
    private final Map<String, Window> admission = new HashMap<>();

    OAuthController(OAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    ProtectedResource resource(HttpServletResponse response) {
        headers(response);
        return new ProtectedResource(
                oauth.resource(),
                List.of(oauth.issuer()),
                OAuthService.SUPPORTED.stream().sorted().toList(),
                List.of("header"));
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    ServerMetadata metadata(HttpServletResponse response) {
        headers(response);
        return new ServerMetadata(
                oauth.issuer(),
                endpoint("authorize"),
                endpoint("token"),
                endpoint("register"),
                endpoint("revoke"),
                List.of("code"),
                List.of("authorization_code", "refresh_token"),
                List.of("none"),
                List.of("S256"),
                true,
                OAuthService.SUPPORTED.stream().sorted().toList());
    }

    @PostMapping(value = "/api/auth/oauth/register", consumes = "application/json")
    ResponseEntity<?> register(@RequestBody Registration body, HttpServletRequest request) {
        admit(request, "register", 5);
        if (body.token_endpoint_auth_method() != null
                        && !body.token_endpoint_auth_method().equals("none")
                || body.grant_types() != null
                        && (body.grant_types().stream().anyMatch(Objects::isNull)
                                || !body.grant_types().contains("authorization_code")
                                || !Set.of("authorization_code", "refresh_token")
                                        .containsAll(body.grant_types()))
                || body.response_types() != null && !body.response_types().equals(List.of("code"))) {
            throw OAuthService.failure("invalid_client_metadata");
        }
        var client =
                oauth.register(body.client_name() == null ? "MCP client" : body.client_name(), body.redirect_uris());
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(new RegisteredClient(
                        client.id(),
                        client.name(),
                        client.redirectUris(),
                        "none",
                        List.of("authorization_code", "refresh_token"),
                        List.of("code")));
    }

    @GetMapping("/api/auth/oauth/authorize")
    ResponseEntity<?> authorize(HttpServletRequest request) {
        admit(request, "authorize", 30);
        var pending = oauth.prepare(
                one(request, "client_id"),
                one(request, "redirect_uri"),
                one(request, "response_type"),
                one(request, "scope"),
                one(request, "state"),
                one(request, "code_challenge"),
                one(request, "code_challenge_method"),
                one(request, "resource"));
        HttpSession session = request.getSession();
        String id = UUID.randomUUID().toString();
        synchronized (session) {
            Map<String, OAuthService.AuthorizationRequest> values = pending(session);
            values.values().removeIf(value -> !value.expiresAt().isAfter(Instant.now()));
            if (values.size() >= 8) {
                throw OAuthService.failure("temporarily_unavailable");
            }
            values.put(id, pending);
            session.setAttribute(PENDING, values);
        }
        return ResponseEntity.status(303)
                .location(URI.create("/connect?request=" + id))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/api/auth/oauth/consent")
    Consent consent(@RequestParam String request, HttpSession session, Authentication authentication) {
        principal(authentication);
        synchronized (session) {
            var value = lookup(session, request);
            return new Consent(value.client().name(), value.redirectUri(), value.scopes());
        }
    }

    @PostMapping("/api/auth/oauth/consent")
    Redirect consent(@RequestBody Decision decision, HttpSession session, Authentication authentication) {
        if (decision.allow() && decision.workspaceId() == null) {
            throw OAuthService.failure("invalid_request");
        }
        synchronized (session) {
            var value = lookup(session, decision.request());
            String redirect = oauth.consent(
                    principal(authentication),
                    decision.allow() ? WorkspaceId.parse(decision.workspaceId()) : null,
                    value,
                    decision.scopes(),
                    decision.allow());
            pending(session).remove(decision.request());
            return new Redirect(redirect);
        }
    }

    @PostMapping(value = "/api/auth/oauth/token", consumes = "application/x-www-form-urlencoded")
    OAuthService.Tokens token(HttpServletRequest request, HttpServletResponse response) {
        headers(response);
        admit(request, "token", 60);
        if (request.getHeader("Authorization") != null) {
            throw OAuthService.failure("invalid_client");
        }
        return switch (String.valueOf(one(request, "grant_type"))) {
            case "authorization_code" ->
                oauth.exchange(
                        one(request, "client_id"),
                        one(request, "code"),
                        one(request, "redirect_uri"),
                        one(request, "code_verifier"),
                        one(request, "resource"));
            case "refresh_token" ->
                oauth.refresh(
                        one(request, "client_id"),
                        one(request, "refresh_token"),
                        one(request, "resource"),
                        one(request, "scope"));
            default -> throw OAuthService.failure("unsupported_grant_type");
        };
    }

    @PostMapping(value = "/api/auth/oauth/revoke", consumes = "application/x-www-form-urlencoded")
    ResponseEntity<?> revoke(HttpServletRequest request) {
        admit(request, "token", 60);
        oauth.revokeToken(one(request, "client_id"), one(request, "token"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/admin/workspaces/{workspaceId}/connections")
    Connections connections(Authentication authentication, @PathVariable String workspaceId) {
        return new Connections(oauth.connections(principal(authentication), WorkspaceId.parse(workspaceId)));
    }

    @DeleteMapping("/api/admin/workspaces/{workspaceId}/connections/{id}")
    ResponseEntity<?> disconnect(
            @PathVariable UUID id, @PathVariable String workspaceId, Authentication authentication) {
        oauth.disconnect(principal(authentication), WorkspaceId.parse(workspaceId), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(OAuthService.Failure.class)
    ResponseEntity<?> invalid(OAuthService.Failure failure) {
        int code = failure.getMessage().equals("temporarily_unavailable")
                ? 429
                : failure.getMessage().equals("access_denied") ? 403 : 400;
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(new Failure(failure.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<?> denied() {
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(new Failure("access_denied"));
    }

    @ExceptionHandler({TransientDataAccessException.class, TransactionTimedOutException.class})
    ResponseEntity<?> busy() {
        return ResponseEntity.status(429)
                .cacheControl(CacheControl.noStore())
                .body(new Failure("temporarily_unavailable"));
    }

    @ExceptionHandler(UncategorizedSQLException.class)
    ResponseEntity<?> databaseLockTimeout(UncategorizedSQLException failure) {
        var sql = failure.getSQLException();
        if (sql != null && ("55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState()))) {
            return busy();
        }
        throw failure;
    }

    private String endpoint(String name) {
        return oauth.issuer() + "/api/auth/oauth/" + name;
    }

    private static AuthPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal value)
                || value.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw OAuthService.failure("access_denied");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OAuthService.AuthorizationRequest> pending(HttpSession session) {
        Object value = session.getAttribute(PENDING);
        if (value == null) {
            value = new HashMap<String, OAuthService.AuthorizationRequest>();
            session.setAttribute(PENDING, value);
        }
        return (Map<String, OAuthService.AuthorizationRequest>) value;
    }

    private static OAuthService.AuthorizationRequest lookup(HttpSession session, String id) {
        var value = pending(session).get(id);
        if (value == null || !value.expiresAt().isAfter(Instant.now())) {
            throw OAuthService.failure("invalid_request");
        }
        return value;
    }

    private static String one(HttpServletRequest request, String key) {
        String[] values = request.getParameterValues(key);
        if (values != null && (values.length != 1 || values[0].length() > 2048)) {
            throw OAuthService.failure("invalid_request");
        }
        return values == null ? null : values[0];
    }

    private synchronized void admit(HttpServletRequest request, String operation, int limit) {
        long minute = System.currentTimeMillis() / 60000;
        admission.values().removeIf(value -> value.minute() != minute);
        String key = operation + ":" + request.getRemoteAddr();
        Window value = admission.get(key);
        if (value == null && admission.size() >= 1024 || value != null && value.count() >= limit) {
            throw OAuthService.failure("temporarily_unavailable");
        }
        admission.put(key, new Window(minute, value == null ? 1 : value.count() + 1));
    }

    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    /**
     * The documents this endpoint publishes. Their component names are the JSON field names that
     * the OAuth metadata specifications fix, so each one is spelled as the wire spells it.
     */
    record ProtectedResource(
            String resource,
            List<String> authorization_servers,
            List<String> scopes_supported,
            List<String> bearer_methods_supported) {}

    record ServerMetadata(
            String issuer,
            String authorization_endpoint,
            String token_endpoint,
            String registration_endpoint,
            String revocation_endpoint,
            List<String> response_types_supported,
            List<String> grant_types_supported,
            List<String> token_endpoint_auth_methods_supported,
            List<String> code_challenge_methods_supported,
            boolean authorization_response_iss_parameter_supported,
            List<String> scopes_supported) {}

    record RegisteredClient(
            String client_id,
            String client_name,
            List<String> redirect_uris,
            String token_endpoint_auth_method,
            List<String> grant_types,
            List<String> response_types) {}

    /** The browser consent view, which is this application's own shape rather than a specified one. */
    record Consent(String clientName, String redirectUri, Set<String> scopes) {}

    record Redirect(String redirect) {}

    record Connections(List<OAuthService.ConnectionInfo> items) {}

    /** An OAuth error body carries the code alone; the reason never leaves the log. */
    record Failure(String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Registration(
            String client_name,
            List<String> redirect_uris,
            String token_endpoint_auth_method,
            List<String> grant_types,
            List<String> response_types) {}

    record Decision(String request, String workspaceId, Set<String> scopes, boolean allow) {}

    private record Window(long minute, int count) {}
}
