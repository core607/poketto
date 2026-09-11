package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OAuthIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    static final String ISSUER = "https://service.example",
            RESOURCE = ISSUER + "/mcp",
            REDIRECT = "https://client.example/callback",
            VERIFIER = "v".repeat(43);
    JdbcTemplate jdbc;
    AuthService auth;
    OAuthService oauth;
    WorkspaceId workspace;
    AuthPrincipal owner;
    OAuthService.Client client;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("truncate table workspaces,auth_accounts,oauth_clients cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default) values (?,'OAuth fixture',true)",
                workspace.value());
        var manager = new DataSourceTransactionManager(ds);
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        String initialize = UUID.randomUUID().toString();
        auth = new AuthService(jdbc, manager, passwords, event -> {}, Clock.systemUTC(), initialize);
        owner = auth.initializeOwner(initialize, "owner", UUID.randomUUID().toString());
        oauth = new OAuthService(jdbc, manager, auth, Clock.systemUTC(), ISSUER);
        client = oauth.register("Test client", List.of(REDIRECT));
    }

    OAuthService.AuthorizationRequest request() {
        return oauth.prepare(
                client.id(),
                REDIRECT,
                "code",
                "repository:execute content:read_private content:publish offline_access",
                "state",
                OAuthService.challenge(VERIFIER),
                "S256",
                RESOURCE);
    }

    String code(Set<String> scope) {
        return parameter(oauth.consent(owner, workspace, request(), scope, true), "code");
    }

    OAuthService.Tokens tokens() {
        return oauth.exchange(
                client.id(), code(Set.of("repository:execute", "offline_access")), REDIRECT, VERIFIER, RESOURCE);
    }

    static String parameter(String uri, String name) {
        for (String pair : URI.create(uri).getRawQuery().split("&")) {
            String[] p = pair.split("=", 2);
            if (p[0].equals(name)) return URLDecoder.decode(p[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError(name);
    }

    @Test
    void consentRestrictsAuthorityAndSeparateConnectionsRevokeIndependently() {
        var a = tokens();
        var b = tokens();
        var principal = auth.authenticateApiKey(a.access_token());
        assertThat(auth.authorize(principal, workspace).capabilities()).containsExactly(Capability.EXECUTE_REPOSITORY);
        assertThatThrownBy(() -> auth.authorize(principal, workspace, Capability.READ_PRIVATE))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> auth.authorize(principal, WorkspaceId.random()))
                .isInstanceOf(AuthException.class);
        oauth.disconnect(owner, workspace, principal.subjectId());
        assertThatThrownBy(() -> auth.authenticateApiKey(a.access_token())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> oauth.refresh(client.id(), a.refresh_token(), RESOURCE, null))
                .hasMessage("invalid_grant");
        assertThat(auth.authenticateApiKey(b.access_token())).isNotNull();
        assertThat(oauth.connections(owner, workspace)).hasSize(2);
    }

    @Test
    void activeConnectionsStayVisibleAfterMoreThanOnePageOfDisconnections() {
        var active = auth.authenticateApiKey(tokens().access_token());
        for (int i = 0; i < 100; i++) {
            var removed = auth.authenticateApiKey(tokens().access_token());
            oauth.disconnect(owner, workspace, removed.subjectId());
        }
        var visible = oauth.connections(owner, workspace);
        assertThat(visible).hasSize(100);
        assertThat(visible.getFirst().id()).isEqualTo(active.subjectId());
        assertThat(visible.getFirst().revoked()).isFalse();
    }

    @Test
    void staleAnonymousRegistrationsAreReclaimedWithoutDeletingConnectionsOrPendingConsent() {
        tokens();
        var waiting = oauth.register("Waiting", List.of(REDIRECT));
        jdbc.update("update oauth_clients set created_at=now()-interval '2 days' where client_id=?", client.id());
        var pending = oauth.prepare(
                waiting.id(),
                REDIRECT,
                "code",
                "repository:execute",
                "state",
                OAuthService.challenge(VERIFIER),
                "S256",
                RESOURCE);
        jdbc.update(
                "insert into oauth_clients(client_id,client_name,redirect_uris,created_at) select 'unused-'||i,'Unused',array['https://client.example/callback'],now()-interval '2 days' from generate_series(1,4094) i");
        oauth.register("New", List.of(REDIRECT));
        assertThat(jdbc.queryForObject("select count(*) from oauth_clients", Integer.class))
                .isEqualTo(3);
        assertThat(oauth.connections(owner, workspace)).hasSize(1);
        assertThat(oauth.consent(owner, workspace, pending, Set.of("repository:execute"), true))
                .contains("code=");
    }

    @Test
    void requestsCannotKeepUnconnectedRegistrationsAlivePastTheirFixedDeadline() {
        jdbc.update("update oauth_clients set created_at=now()-interval '23 hours 55 minutes'");
        var pending = request();
        assertThat(pending.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(301));
        assertThatThrownBy(() -> oauth.prepare(
                        client.id(),
                        "https://bad.example",
                        "code",
                        null,
                        "state",
                        OAuthService.challenge(VERIFIER),
                        "S256",
                        RESOURCE))
                .hasMessage("invalid_request");
        jdbc.update("update oauth_clients set created_at=now()-interval '2 days'");
        assertThatThrownBy(this::request).hasMessage("invalid_client");
        oauth.register("Next", List.of(REDIRECT));
        assertThatThrownBy(() -> oauth.consent(owner, workspace, pending, Set.of("repository:execute"), true))
                .hasMessage("invalid_request");
    }

    @Test
    void cleanupRetiresOnlyOldInactiveOAuthKeysAndStaticKeyListingExcludesConnections() {
        var staticKey = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        var active = tokens();
        var retired = auth.authenticateApiKey(tokens().access_token());
        var recent = auth.authenticateApiKey(tokens().access_token());
        oauth.disconnect(owner, workspace, retired.subjectId());
        oauth.disconnect(owner, workspace, recent.subjectId());
        jdbc.update("update auth_api_keys set revoked_at=now()-interval '31 days' where key_id=?", retired.subjectId());
        var expired = auth.authenticateApiKey(tokens().access_token());
        jdbc.update(
                "update oauth_connections set expires_at=now()-interval '31 days' where key_id=?", expired.subjectId());
        oauth.register("Cleanup", List.of(REDIRECT));
        assertThat(oauth.connections(owner, workspace)).hasSize(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_api_keys where key_id in (?,?)",
                        Integer.class,
                        retired.subjectId(),
                        expired.subjectId()))
                .isZero();
        assertThat(auth.authenticateApiKey(active.access_token())).isNotNull();
        assertThat(auth.authenticateApiKey(staticKey.token())).isNotNull();
        assertThat(auth.listApiKeys(owner, workspace, 0, 100).items())
                .extracting(AuthService.ApiKeyInfo::id)
                .containsExactly(staticKey.id());
    }

    @Test
    void cleanupWaitsForTheWorkspaceBeforeLockingKeysAndTheirCodeRows() throws Exception {
        var key = auth.authenticateApiKey(tokens().access_token()).subjectId();
        jdbc.update("update oauth_connections set expires_at=now()-interval '31 days' where key_id=?", key);
        try (var pool = Executors.newSingleThreadExecutor();
                var held = jdbc.getDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (var workspaceLock = held.prepareStatement(
                            "select workspace_id from workspaces where workspace_id=? for update");
                    var codeLock = held.prepareStatement("select digest from oauth_codes where key_id=? for update")) {
                workspaceLock.setObject(1, workspace.value());
                workspaceLock.executeQuery().close();
                codeLock.setObject(1, key);
                codeLock.executeQuery().close();
                var cleanup = pool.submit(() -> oauth.register("Concurrent cleanup", List.of(REDIRECT)));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (jdbc.queryForObject(
                                "select count(*) from pg_stat_activity where pid<>pg_backend_pid() and datname=current_database() and wait_event_type='Lock' and query like '%oauth_%'",
                                Integer.class)
                        == 0) {
                    if (System.nanoTime() >= deadline) throw new AssertionError("cleanup did not wait on a lock");
                    Thread.sleep(10);
                }
                // This is the workspace -> code -> key order used by proven authorization-code replay.
                try (var revoke = held.prepareStatement("update auth_api_keys set revoked_at=now() where key_id=?")) {
                    revoke.setQueryTimeout(2);
                    revoke.setObject(1, key);
                    revoke.executeUpdate();
                }
                held.commit();
                assertThat(cleanup.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isNotNull();
            }
        }
    }

    @Test
    void changedIssuerMarksStoredConnectionsAsNeedingNewConsent() {
        var issued = tokens();
        oauth = new OAuthService(
                jdbc,
                new DataSourceTransactionManager(jdbc.getDataSource()),
                auth,
                Clock.systemUTC(),
                "https://changed.example");
        assertThatThrownBy(() -> auth.authenticateApiKey(issued.access_token())).isInstanceOf(AuthException.class);
        var item = oauth.connections(owner, workspace).getFirst();
        assertThat(item.requiresReauthorization()).isTrue();
        assertThat(item.revoked()).isFalse();
        oauth.disconnect(owner, workspace, item.id());
        assertThat(oauth.connections(owner, workspace).getFirst().revoked()).isTrue();
    }

    @Test
    void codesBindClientRedirectResourceAndPkceAndReplayCommitsRevocation() {
        String code = code(Set.of("repository:execute", "offline_access"));
        String other = oauth.register("Other", List.of(REDIRECT)).id();
        assertThatThrownBy(() -> oauth.exchange(other, code, REDIRECT, VERIFIER, RESOURCE))
                .hasMessage("invalid_grant");
        assertThatThrownBy(() -> oauth.exchange(client.id(), code, "https://evil.example", VERIFIER, RESOURCE))
                .hasMessage("invalid_grant");
        assertThatThrownBy(() -> oauth.exchange(client.id(), code, REDIRECT, "x".repeat(43), RESOURCE))
                .hasMessage("invalid_grant");
        assertThatThrownBy(() -> oauth.exchange(client.id(), code, REDIRECT, VERIFIER, "https://other.example/mcp"))
                .hasMessage("invalid_target");
        var issued = oauth.exchange(client.id(), code, REDIRECT, VERIFIER, RESOURCE);
        assertThatThrownBy(() -> oauth.exchange(client.id(), code, REDIRECT, VERIFIER, RESOURCE))
                .hasMessage("invalid_grant");
        assertThatThrownBy(() -> auth.authenticateApiKey(issued.access_token())).isInstanceOf(AuthException.class);
    }

    @Test
    void rotatingRefreshDetectsReuseAndPreventsEscalation() {
        var a = tokens();
        assertThatThrownBy(() -> oauth.refresh(client.id(), a.refresh_token(), RESOURCE, "content:publish"))
                .hasMessage("invalid_scope");
        assertThatThrownBy(() -> oauth.refresh(client.id(), a.refresh_token(), "https://other.example/mcp", null))
                .hasMessage("invalid_target");
        var b = oauth.refresh(client.id(), a.refresh_token(), null, null);
        assertThat(b.refresh_token()).isNotEqualTo(a.refresh_token());
        assertThat(auth.authenticateApiKey(b.access_token())).isNotNull();
        assertThatThrownBy(() -> oauth.refresh(client.id(), a.refresh_token(), RESOURCE, null))
                .hasMessage("invalid_grant");
        assertThatThrownBy(() -> auth.authenticateApiKey(b.access_token())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> oauth.refresh(client.id(), b.refresh_token(), RESOURCE, null))
                .hasMessage("invalid_grant");
    }

    @Test
    void expirationAndDisablingIssuerPreserveBoundaries() {
        var a = tokens();
        jdbc.update("update oauth_access_tokens set expires_at=now()-interval '1 second'");
        assertThatThrownBy(() -> auth.authenticateApiKey(a.access_token())).isInstanceOf(AuthException.class);
        var b = oauth.refresh(client.id(), a.refresh_token(), RESOURCE, null);
        auth.oauthResource("");
        assertThatThrownBy(() -> auth.authenticateApiKey(b.access_token())).isInstanceOf(AuthException.class);
        auth.oauthResource(RESOURCE);
        assertThat(auth.authenticateApiKey(b.access_token())).isNotNull();
        jdbc.update("update oauth_connections set expires_at=now()-interval '1 second'");
        assertThatThrownBy(() -> auth.authenticateApiKey(b.access_token())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> oauth.refresh(client.id(), b.refresh_token(), RESOURCE, null))
                .hasMessage("invalid_grant");
    }

    @Test
    void rejectsImplicitPlainPkceUnsafeRedirectsAndUnrequestedScopes() {
        for (String bad : List.of(
                "http://client.example/cb",
                "https://client.example/cb#x",
                "https://name@client.example/cb",
                "javascript:alert(1)"))
            assertThatThrownBy(() -> oauth.register("Bad", List.of(bad))).isInstanceOf(OAuthService.Failure.class);
        assertThatThrownBy(() -> oauth.prepare(
                        client.id(), REDIRECT, "token", null, "s", OAuthService.challenge(VERIFIER), "S256", RESOURCE))
                .hasMessage("unsupported_response_type");
        assertThatThrownBy(() -> oauth.prepare(client.id(), REDIRECT, "code", null, "s", VERIFIER, "plain", RESOURCE))
                .hasMessage("invalid_request");
        assertThatThrownBy(() -> oauth.consent(owner, workspace, request(), Set.of("MANAGE_KEYS"), true))
                .hasMessage("invalid_scope");
        String denied = oauth.consent(owner, workspace, request(), Set.of(), false);
        assertThat(parameter(denied, "error")).isEqualTo("access_denied");
        assertThat(parameter(denied, "iss")).isEqualTo(ISSUER);
        assertThat(jdbc.queryForObject("select count(*) from oauth_connections", Integer.class))
                .isZero();
    }

    @Test
    void concurrentRefreshHasOneWinnerAndReplayRevokesItsToken() throws Exception {
        var first = tokens();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<OAuthService.Tokens> call = () -> {
                start.await();
                try {
                    return oauth.refresh(client.id(), first.refresh_token(), RESOURCE, null);
                } catch (OAuthService.Failure e) {
                    return null;
                }
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            var aa = a.get();
            var bb = b.get();
            assertThat((aa == null) != (bb == null)).isTrue();
            String token = aa == null ? bb.access_token() : aa.access_token();
            assertThatThrownBy(() -> auth.authenticateApiKey(token)).isInstanceOf(AuthException.class);
        }
    }
}
