package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.OAuthService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "poketto.oauth.issuer")
class OAuthController {
    private static final String PENDING = OAuthController.class.getName() + ".requests";
    private final OAuthService oauth;
    private final WorkspaceCatalog workspaces;
    private final Map<String, Window> admission = new HashMap<>();

    OAuthController(OAuthService oauth, WorkspaceCatalog workspaces) {
        this.oauth = oauth;
        this.workspaces = workspaces;
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    Map<String, Object> resource(HttpServletResponse response) {
        headers(response);
        return Map.of(
                "resource",
                oauth.resource(),
                "authorization_servers",
                List.of(oauth.issuer()),
                "scopes_supported",
                OAuthService.SUPPORTED.stream().sorted().toList(),
                "bearer_methods_supported",
                List.of("header"));
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    Map<String, Object> metadata(HttpServletResponse response) {
        headers(response);
        return Map.ofEntries(
                Map.entry("issuer", oauth.issuer()),
                Map.entry("authorization_endpoint", endpoint("authorize")),
                Map.entry("token_endpoint", endpoint("token")),
                Map.entry("registration_endpoint", endpoint("register")),
                Map.entry("revocation_endpoint", endpoint("revoke")),
                Map.entry("response_types_supported", List.of("code")),
                Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token")),
                Map.entry("token_endpoint_auth_methods_supported", List.of("none")),
                Map.entry("code_challenge_methods_supported", List.of("S256")),
                Map.entry("authorization_response_iss_parameter_supported", true),
                Map.entry(
                        "scopes_supported",
                        OAuthService.SUPPORTED.stream().sorted().toList()));
    }

    @PostMapping(value = "/api/auth/oauth/register", consumes = "application/json")
    ResponseEntity<?> register(@RequestBody Registration body, HttpServletRequest request) {
        admit(request, "register", 5);
        if (body.token_endpoint_auth_method() != null
                        && !body.token_endpoint_auth_method().equals("none")
                || body.grant_types() != null
                        && (body.grant_types().stream().anyMatch(java.util.Objects::isNull)
                                || !body.grant_types().contains("authorization_code")
                                || !Set.of("authorization_code", "refresh_token")
                                        .containsAll(body.grant_types()))
                || body.response_types() != null && !body.response_types().equals(List.of("code")))
            throw OAuthService.failure("invalid_client_metadata");
        var client =
                oauth.register(body.client_name() == null ? "MCP client" : body.client_name(), body.redirect_uris());
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "client_id",
                        client.id(),
                        "client_name",
                        client.name(),
                        "redirect_uris",
                        client.redirectUris(),
                        "token_endpoint_auth_method",
                        "none",
                        "grant_types",
                        List.of("authorization_code", "refresh_token"),
                        "response_types",
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
            if (values.size() >= 8) throw OAuthService.failure("temporarily_unavailable");
            values.put(id, pending);
            session.setAttribute(PENDING, values);
        }
        return ResponseEntity.status(303)
                .location(URI.create("/connect?request=" + id))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/api/auth/oauth/consent")
    Map<String, Object> consent(@RequestParam String request, HttpSession session, Authentication authentication) {
        oauth.requireOwner(
                principal(authentication), workspaces.defaultWorkspace().id());
        synchronized (session) {
            var value = lookup(session, request);
            return Map.of(
                    "clientName", value.client().name(), "redirectUri", value.redirectUri(), "scopes", value.scopes());
        }
    }

    @PostMapping("/api/auth/oauth/consent")
    Map<String, String> consent(@RequestBody Decision decision, HttpSession session, Authentication authentication) {
        synchronized (session) {
            var value = lookup(session, decision.request());
            String redirect = oauth.consent(
                    principal(authentication),
                    workspaces.defaultWorkspace().id(),
                    value,
                    decision.scopes(),
                    decision.allow());
            pending(session).remove(decision.request());
            return Map.of("redirect", redirect);
        }
    }

    @PostMapping(value = "/api/auth/oauth/token", consumes = "application/x-www-form-urlencoded")
    OAuthService.Tokens token(HttpServletRequest request, HttpServletResponse response) {
        headers(response);
        admit(request, "token", 60);
        if (request.getHeader("Authorization") != null) throw OAuthService.failure("invalid_client");
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

    @GetMapping("/api/admin/connections")
    Map<String, Object> connections(Authentication authentication) {
        return Map.of(
                "items",
                oauth.connections(
                        principal(authentication), workspaces.defaultWorkspace().id()));
    }

    @DeleteMapping("/api/admin/connections/{id}")
    ResponseEntity<?> disconnect(@PathVariable UUID id, Authentication authentication) {
        oauth.disconnect(
                principal(authentication), workspaces.defaultWorkspace().id(), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(OAuthService.Failure.class)
    ResponseEntity<?> invalid(OAuthService.Failure failure) {
        int code = failure.getMessage().equals("temporarily_unavailable")
                ? 429
                : failure.getMessage().equals("access_denied") ? 403 : 400;
        return ResponseEntity.status(code)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<?> denied() {
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(Map.of("error", "access_denied"));
    }

    @ExceptionHandler({
        org.springframework.dao.TransientDataAccessException.class,
        org.springframework.transaction.TransactionTimedOutException.class
    })
    ResponseEntity<?> busy() {
        return ResponseEntity.status(429)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "temporarily_unavailable"));
    }

    @ExceptionHandler(org.springframework.jdbc.UncategorizedSQLException.class)
    ResponseEntity<?> databaseLockTimeout(org.springframework.jdbc.UncategorizedSQLException failure) {
        var sql = failure.getSQLException();
        if (sql != null && ("55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState()))) return busy();
        throw failure;
    }

    private String endpoint(String name) {
        return oauth.issuer() + "/api/auth/oauth/" + name;
    }

    private static AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal value))
            throw OAuthService.failure("access_denied");
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
        if (value == null || !value.expiresAt().isAfter(Instant.now())) throw OAuthService.failure("invalid_request");
        return value;
    }

    private static String one(HttpServletRequest request, String key) {
        String[] values = request.getParameterValues(key);
        if (values != null && (values.length != 1 || values[0].length() > 2048))
            throw OAuthService.failure("invalid_request");
        return values == null ? null : values[0];
    }

    private synchronized void admit(HttpServletRequest request, String operation, int limit) {
        long minute = System.currentTimeMillis() / 60000;
        admission.values().removeIf(value -> value.minute() != minute);
        String key = operation + ":" + request.getRemoteAddr();
        Window value = admission.get(key);
        if (value == null && admission.size() >= 1024 || value != null && value.count() >= limit)
            throw OAuthService.failure("temporarily_unavailable");
        admission.put(key, new Window(minute, value == null ? 1 : value.count() + 1));
    }

    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Registration(
            String client_name,
            List<String> redirect_uris,
            String token_endpoint_auth_method,
            List<String> grant_types,
            List<String> response_types) {}

    record Decision(String request, Set<String> scopes, boolean allow) {}

    private record Window(long minute, int count) {}
}
