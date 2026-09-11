package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opaque OAuth grants delegate to existing revocable workspace keys; no token carries cached authority. */
public final class OAuthService {
    public static final Map<String, Capability> SCOPES = Map.of(
            "repository:execute", Capability.EXECUTE_REPOSITORY,
            "content:read_private", Capability.READ_PRIVATE,
            "content:write_private", Capability.WRITE_PRIVATE,
            "content:publish", Capability.PUBLISH);
    public static final Set<String> SUPPORTED = Set.of(
            "repository:execute", "content:read_private", "content:write_private", "content:publish", "offline_access");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuthService auth;
    private final Clock clock;
    private final String issuer;
    private final SecureRandom random = new SecureRandom();

    public OAuthService(
            JdbcTemplate jdbc, PlatformTransactionManager manager, AuthService auth, Clock clock, String issuer) {
        URI uri = URI.create(issuer);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !uri.getRawPath().isEmpty())
            throw new IllegalArgumentException("OAuth issuer must be an HTTPS origin without a trailing slash");
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
        this.auth = auth;
        this.clock = clock;
        this.issuer = issuer;
        auth.oauthResource(resource());
    }

    public String issuer() {
        return issuer;
    }

    public String resource() {
        return issuer + "/mcp";
    }

    public Client register(String name, List<String> redirects) {
        if (name == null
                || name.isBlank()
                || name.length() > 100
                || name.chars().anyMatch(Character::isISOControl)
                || redirects == null
                || redirects.isEmpty()
                || redirects.size() > 8) throw failure("invalid_client_metadata");
        redirects.forEach(OAuthService::validateRedirect);
        return tx.execute(status -> {
            registryLock();
            cleanup("");
            if (jdbc.queryForObject("select count(*) from oauth_clients", Integer.class) >= 4096)
                throw failure("temporarily_unavailable");
            String id = token("oc_");
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "insert into oauth_clients(client_id,client_name,redirect_uris,created_at) values (?,?,?,?)");
                statement.setString(1, id);
                statement.setString(2, name);
                statement.setArray(3, connection.createArrayOf("text", redirects.toArray(String[]::new)));
                statement.setTimestamp(4, now());
                return statement;
            });
            return new Client(id, name, List.copyOf(redirects), clock.instant().plus(Duration.ofDays(1)));
        });
    }

    public AuthorizationRequest prepare(
            String clientId,
            String redirect,
            String responseType,
            String scope,
            String state,
            String challenge,
            String challengeMethod,
            String resource) {
        Client client = client(clientId);
        if (client.unconnectedUntil() != null && !client.unconnectedUntil().isAfter(clock.instant()))
            throw failure("invalid_client");
        if (!client.redirectUris().contains(redirect)) throw failure("invalid_request");
        if (!"code".equals(responseType)) throw failure("unsupported_response_type");
        if (!"S256".equals(challengeMethod) || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}"))
            throw failure("invalid_request");
        if (state == null
                || state.length() < 1
                || state.length() > 1024
                || state.chars().anyMatch(Character::isISOControl)) throw failure("invalid_request");
        requireResource(resource);
        Set<String> scopes = scopes(scope == null || scope.isBlank() ? "repository:execute offline_access" : scope);
        Instant expires = clock.instant().plus(Duration.ofMinutes(10));
        if (client.unconnectedUntil() != null && client.unconnectedUntil().isBefore(expires))
            expires = client.unconnectedUntil();
        return new AuthorizationRequest(client, redirect, scopes, state, challenge, expires);
    }

    public String consent(
            AuthPrincipal actor,
            WorkspaceId workspace,
            AuthorizationRequest request,
            Set<String> selected,
            boolean allow) {
        requireOwner(actor, workspace);
        if (!request.expiresAt().isAfter(clock.instant())) throw failure("invalid_request");
        if (!allow) return callback(request, "error", "access_denied");
        if (selected == null
                || selected.isEmpty()
                || selected.stream().anyMatch(java.util.Objects::isNull)
                || !request.scopes().containsAll(selected)) throw failure("invalid_scope");
        Set<Capability> capabilities =
                selected.stream().filter(SCOPES::containsKey).map(SCOPES::get).collect(Collectors.toSet());
        if (capabilities.isEmpty()) throw failure("invalid_scope");
        return tx.execute(status -> {
            // Final consent and registry cleanup cannot race a connection's client foreign key.
            registryLock();
            cleanup(request.client().id());
            lock(workspace);
            // Pin registration until the new connection commits; cleanup never deletes a live consent.
            var registered = jdbc.query(
                    "select client_id from oauth_clients where client_id=? for update",
                    (rs, row) -> rs.getString(1),
                    request.client().id());
            if (registered.isEmpty() || !request.expiresAt().isAfter(clock.instant())) throw failure("invalid_request");
            if (jdbc.queryForObject(
                            "select count(*) from oauth_connections c join auth_api_keys k using(key_id) where c.workspace_id=? and k.revoked_at is null and c.expires_at>? and c.resource=?",
                            Integer.class,
                            workspace.value(),
                            now(),
                            resource())
                    >= 100) throw failure("temporarily_unavailable");
            IssuedToken key = auth.createApiKey(actor, workspace, actor.accountId(), capabilities);
            jdbc.update(
                    "insert into oauth_connections(key_id,client_id,workspace_id,account_id,scopes,created_at,expires_at,resource) values (?,?,?,?,?,?,?,?)",
                    key.id(),
                    request.client().id(),
                    workspace.value(),
                    actor.accountId(),
                    scopeString(selected),
                    now(),
                    after(Duration.ofDays(90)),
                    resource());
            String code = token("code_");
            jdbc.update(
                    "insert into oauth_codes(digest,key_id,redirect_uri,challenge,expires_at) values (?,?,?,?,?)",
                    digest(code),
                    key.id(),
                    request.redirectUri(),
                    request.challenge(),
                    after(Duration.ofMinutes(5)));
            return callback(request, "code", code);
        });
    }

    public Tokens exchange(String clientId, String code, String redirect, String verifier, String resource) {
        requireResource(resource);
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) throw failure("invalid_grant");
        Connection grant = findGrant("oauth_codes", code, clientId);
        Tokens result = tx.execute(status -> {
            lock(grant.workspace());
            var rows = jdbc.query(
                    "select redirect_uri,challenge,expires_at,used_at from oauth_codes where digest=? for update",
                    (rs, row) -> new Code(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getTimestamp(3).toInstant(),
                            rs.getTimestamp(4) != null),
                    digest(code));
            if (rows.isEmpty()) throw failure("invalid_grant");
            Code value = rows.getFirst();
            if (!value.redirect().equals(redirect) || !constantEquals(value.challenge(), challenge(verifier)))
                throw failure("invalid_grant");
            if (value.used()) {
                revoke(grant);
                return null;
            }
            if (!value.expires().isAfter(clock.instant())) throw failure("invalid_grant");
            validateGrant(grant);
            jdbc.update("update oauth_codes set used_at=? where digest=?", now(), digest(code));
            return issue(grant);
        });
        // Replay revocation must commit even though the protocol response is an error.
        if (result == null) throw failure("invalid_grant");
        return result;
    }

    public Tokens refresh(String clientId, String refresh, String resource, String requestedScope) {
        // A refresh without an explicit resource remains bound to its stored grant and this issuer.
        if (resource != null) requireResource(resource);
        Connection grant = findGrant("oauth_refresh_tokens", refresh, clientId);
        if (requestedScope != null && !scopes(requestedScope).equals(scopes(grant.scopes())))
            throw failure("invalid_scope");
        Tokens result = tx.execute(status -> {
            lock(grant.workspace());
            var rows = jdbc.query(
                    "select expires_at,used_at from oauth_refresh_tokens where digest=? for update",
                    (rs, row) -> new Refresh(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) != null),
                    digest(refresh));
            if (rows.isEmpty()) throw failure("invalid_grant");
            Refresh value = rows.getFirst();
            if (value.used()) {
                revoke(grant);
                return null;
            }
            if (!value.expires().isAfter(clock.instant())) throw failure("invalid_grant");
            validateGrant(grant);
            jdbc.update("update oauth_refresh_tokens set used_at=? where digest=?", now(), digest(refresh));
            return issue(grant);
        });
        if (result == null) throw failure("invalid_grant");
        return result;
    }

    public List<ConnectionInfo> connections(AuthPrincipal actor, WorkspaceId workspace) {
        requireOwner(actor, workspace);
        return jdbc.query(
                "select c.key_id,cl.client_name,c.scopes,c.created_at,c.expires_at,k.revoked_at,c.resource from oauth_connections c join oauth_clients cl using(client_id) join auth_api_keys k using(key_id) where c.workspace_id=? order by (k.revoked_at is null and c.expires_at>? and c.resource=?) desc,c.created_at desc limit 100",
                (rs, row) -> new ConnectionInfo(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        scopes(rs.getString(3)),
                        rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) != null,
                        !resource().equals(rs.getString(7))),
                workspace.value(),
                now(),
                resource());
    }

    public void disconnect(AuthPrincipal actor, WorkspaceId workspace, UUID id) {
        requireOwner(actor, workspace);
        auth.revokeApiKey(actor, workspace, id);
    }

    public void revokeToken(String clientId, String token) {
        Connection grant;
        try {
            grant = findGrant("oauth_refresh_tokens", token, clientId);
        } catch (Failure ignored) {
            try {
                grant = findGrant("oauth_access_tokens", token, clientId);
            } catch (Failure absent) {
                return;
            }
        }
        Connection selected = grant;
        tx.executeWithoutResult(status -> {
            lock(selected.workspace());
            revoke(selected);
        });
    }

    private Tokens issue(Connection grant) {
        String access = token("oa_");
        String refresh = scopes(grant.scopes()).contains("offline_access") ? token("or_") : null;
        Instant end = clock.instant().plus(Duration.ofMinutes(10));
        if (end.isAfter(grant.expires())) end = grant.expires();
        jdbc.update("delete from oauth_access_tokens where expires_at <= ?", now());
        jdbc.update("delete from oauth_refresh_tokens where expires_at <= ?", now());
        jdbc.update(
                "delete from oauth_codes where expires_at <= ? and (used_at is null or key_id in (select key_id from oauth_connections where expires_at<=?))",
                now(),
                now());
        jdbc.update(
                "insert into oauth_access_tokens(digest,key_id,expires_at) values (?,?,?)",
                digest(access),
                grant.key(),
                Timestamp.from(end));
        if (refresh != null) {
            Instant refreshEnd = clock.instant().plus(Duration.ofDays(30));
            if (refreshEnd.isAfter(grant.expires())) refreshEnd = grant.expires();
            jdbc.update(
                    "insert into oauth_refresh_tokens(digest,key_id,expires_at) values (?,?,?)",
                    digest(refresh),
                    grant.key(),
                    Timestamp.from(refreshEnd));
        }
        return new Tokens(
                access, "Bearer", Duration.between(clock.instant(), end).toSeconds(), refresh, grant.scopes());
    }

    private void cleanup(String keepClient) {
        Timestamp retiredBefore = Timestamp.from(clock.instant().minus(Duration.ofDays(30)));
        // Match issuance/replay's workspace -> key/token order before cascading key deletion.
        jdbc.query(
                "select w.workspace_id from workspaces w where exists (select 1 from oauth_connections c join auth_api_keys k using(key_id) where c.workspace_id=w.workspace_id and (c.expires_at<? or k.revoked_at<?)) order by w.workspace_id for update",
                (rs, row) -> rs.getObject(1, UUID.class),
                retiredBefore,
                retiredBefore);
        jdbc.update(
                "delete from auth_api_keys k using oauth_connections c where c.key_id=k.key_id and (c.expires_at<? or k.revoked_at<?)",
                retiredBefore,
                retiredBefore);
        jdbc.update(
                "delete from oauth_clients cl where cl.client_id<>? and cl.created_at<? and not exists (select 1 from oauth_connections c where c.client_id=cl.client_id)",
                keepClient,
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
    }

    private void validateGrant(Connection grant) {
        if (!grant.expires().isAfter(clock.instant())) throw failure("invalid_grant");
        try {
            auth.authorize(
                    new AuthPrincipal(AuthPrincipal.Kind.API_KEY, grant.key(), grant.account()), grant.workspace());
        } catch (AuthException rejected) {
            throw failure("invalid_grant");
        }
    }

    private void revoke(Connection grant) {
        auth.revokeOAuthKey(grant.workspace(), grant.key());
    }

    private Connection findGrant(String table, String token, String clientId) {
        if (!Set.of("oauth_codes", "oauth_access_tokens", "oauth_refresh_tokens")
                .contains(table)) throw new IllegalArgumentException();
        var rows = jdbc.query(
                "select c.key_id,c.workspace_id,c.account_id,c.scopes,c.expires_at from oauth_connections c join "
                        + table + " t using(key_id) where t.digest=? and c.client_id=? and c.resource=?",
                (rs, row) -> new Connection(
                        rs.getObject(1, UUID.class),
                        new WorkspaceId(rs.getObject(2, UUID.class)),
                        rs.getObject(3, UUID.class),
                        rs.getString(4),
                        rs.getTimestamp(5).toInstant()),
                digest(token),
                clientId,
                resource());
        if (rows.isEmpty()) throw failure("invalid_grant");
        return rows.getFirst();
    }

    private Client client(String id) {
        var rows = jdbc.query(
                "select cl.client_id,cl.client_name,cl.redirect_uris,case when exists (select 1 from oauth_connections c where c.client_id=cl.client_id) then null else cl.created_at + interval '1 day' end from oauth_clients cl where cl.client_id=?",
                (rs, row) -> new Client(
                        rs.getString(1),
                        rs.getString(2),
                        List.of((String[]) rs.getArray(3).getArray()),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                id);
        if (rows.isEmpty()) throw failure("invalid_client");
        return rows.getFirst();
    }

    public void requireOwner(AuthPrincipal principal, WorkspaceId workspace) {
        if (principal == null
                || principal.kind() != AuthPrincipal.Kind.ACCOUNT
                || auth.authorize(principal, workspace).role() != MembershipRole.OWNER) throw failure("access_denied");
    }

    private void lock(WorkspaceId workspace) {
        jdbc.execute("set local lock_timeout='2s'");
        jdbc.queryForObject(
                "select workspace_id from workspaces where workspace_id=? for update", UUID.class, workspace.value());
    }

    private void registryLock() {
        jdbc.execute("set local lock_timeout='2s'");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(70701109)", Boolean.class)))
            throw failure("temporarily_unavailable");
    }

    private void requireResource(String value) {
        if (!resource().equals(value)) throw failure("invalid_target");
    }

    public static Set<String> scopes(String value) {
        if (value == null || value.length() > 512) throw failure("invalid_scope");
        Set<String> values = new LinkedHashSet<>(Arrays.asList(value.split(" ")));
        if (values.isEmpty() || !SUPPORTED.containsAll(values)) throw failure("invalid_scope");
        return Set.copyOf(values);
    }

    private String callback(AuthorizationRequest r, String key, String value) {
        return r.redirectUri() + (r.redirectUri().contains("?") ? "&" : "?") + key + "=" + encode(value) + "&state="
                + encode(r.state()) + "&iss=" + encode(issuer);
    }

    public static void validateRedirect(String value) {
        if (value == null) throw failure("invalid_redirect_uri");
        try {
            URI uri = URI.create(value);
            if (value.length() > 2048
                    || !"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || value.indexOf('\\') >= 0
                    || value.chars().anyMatch(Character::isISOControl)) throw failure("invalid_redirect_uri");
        } catch (IllegalArgumentException invalid) {
            throw failure("invalid_redirect_uri");
        }
    }

    public static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash(verifier));
    }

    private static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String digest(String value) {
        if (value == null || value.length() > 256) throw failure("invalid_grant");
        return HexFormat.of().formatHex(hash(value));
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String token(String prefix) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private Timestamp after(Duration duration) {
        return Timestamp.from(clock.instant().plus(duration));
    }

    private static String scopeString(Set<String> scopes) {
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static Failure failure(String code) {
        return new Failure(code);
    }

    public static final class Failure extends RuntimeException {
        public Failure(String code) {
            super(code);
        }
    }

    public record Client(String id, String name, List<String> redirectUris, Instant unconnectedUntil) {}

    public record AuthorizationRequest(
            Client client, String redirectUri, Set<String> scopes, String state, String challenge, Instant expiresAt) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Tokens(String access_token, String token_type, long expires_in, String refresh_token, String scope) {
        @Override
        public String toString() {
            return "OAuthTokens[REDACTED]";
        }
    }

    public record ConnectionInfo(
            UUID id,
            String clientName,
            Set<String> scopes,
            Instant createdAt,
            Instant expiresAt,
            boolean revoked,
            boolean requiresReauthorization) {}

    private record Connection(UUID key, WorkspaceId workspace, UUID account, String scopes, Instant expires) {}

    private record Code(String redirect, String challenge, Instant expires, boolean used) {}

    private record Refresh(Instant expires, boolean used) {}
}
