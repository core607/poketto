package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Browser sessions as production stores them: rows in PostgreSQL through Spring Session, reached
 * over real HTTP with the session cookie. Other integration tests keep container sessions.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=")
@Import(RemoteRepositoryIntegrationConfiguration.class)
class BrowserSessionStoreIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    private static final int ANONYMOUS_SECONDS = 30 * 60;
    private static final int ACCOUNT_SECONDS = 90 * 24 * 60 * 60;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    ObjectMapper json;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", () -> directory.toString());
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            // Synthetic repository for application startup, independent of the session store.
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        registry.add("poketto.test.repository-path", remote::toString);
    }

    @BeforeEach
    void resetIdentities() {
        jdbc.execute("truncate table spring_session cascade");
        jdbc.execute("truncate table auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at = null");
    }

    @Test
    void signedInSessionsLiveInPostgresAndKeepTheLongIdleTimeoutUntilLogout() throws Exception {
        String password = UUID.randomUUID().toString() + UUID.randomUUID();
        AuthPrincipal owner = auth.initializeOwner("session-owner", password);
        try (var client = HttpClient.newHttpClient()) {
            HttpResponse<String> guest = send(client, get("/api/auth/csrf", null));
            assertThat(guest.statusCode()).isEqualTo(200);
            String setCookie = guest.headers().firstValue("set-cookie").orElseThrow();
            assertThat(setCookie)
                    .contains("Max-Age=" + 400L * 24 * 60 * 60)
                    .contains("HttpOnly")
                    .containsIgnoringCase("SameSite=Lax");
            String guestCookie = setCookie.split(";", 2)[0];
            assertThat(idleSeconds(guestCookie)).isEqualTo(ANONYMOUS_SECONDS);

            JsonNode token = json.readTree(guest.body());
            HttpResponse<String> login = send(
                    client,
                    HttpRequest.newBuilder(uri("/api/auth/login"))
                            .header("Cookie", guestCookie)
                            .header(
                                    token.get("headerName").stringValue(),
                                    token.get("token").stringValue())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "username=session-owner&password=" + password, StandardCharsets.UTF_8)));
            assertThat(login.statusCode()).isEqualTo(204);
            String cookie =
                    login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            assertThat(cookie).isNotEqualTo(guestCookie);

            // Every request reads the stored context back from PostgreSQL through the attribute filter.
            assertThat(send(client, get("/api/auth/account", cookie)).statusCode())
                    .isEqualTo(200);
            assertThat(idleSeconds(cookie)).isEqualTo(ACCOUNT_SECONDS);
            assertThat(jdbc.queryForObject(
                            "select principal_name from spring_session where session_id = ?",
                            String.class,
                            sessionId(cookie)))
                    .isEqualTo("ACCOUNT:" + owner.subjectId());

            JsonNode signedInToken =
                    json.readTree(send(client, get("/api/auth/csrf", cookie)).body());
            HttpResponse<String> logout = send(
                    client,
                    HttpRequest.newBuilder(uri("/api/auth/logout"))
                            .header("Cookie", cookie)
                            .header(
                                    signedInToken.get("headerName").stringValue(),
                                    signedInToken.get("token").stringValue())
                            .POST(HttpRequest.BodyPublishers.noBody()));
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(jdbc.queryForObject(
                            "select count(*) from spring_session where session_id = ?",
                            Integer.class,
                            sessionId(cookie)))
                    .isZero();
            assertThat(send(client, get("/api/auth/account", cookie)).statusCode())
                    .isEqualTo(401);
        }
    }

    @Test
    void anUnreadableStoredContextSignsTheVisitorOutInsteadOfFailing() throws Exception {
        String password = UUID.randomUUID().toString() + UUID.randomUUID();
        auth.initializeOwner("stale-owner", password);
        try (var client = HttpClient.newHttpClient()) {
            HttpResponse<String> guest = send(client, get("/api/auth/csrf", null));
            String guestCookie =
                    guest.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            JsonNode token = json.readTree(guest.body());
            HttpResponse<String> login = send(
                    client,
                    HttpRequest.newBuilder(uri("/api/auth/login"))
                            .header("Cookie", guestCookie)
                            .header(
                                    token.get("headerName").stringValue(),
                                    token.get("token").stringValue())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "username=stale-owner&password=" + password, StandardCharsets.UTF_8)));
            String cookie =
                    login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];

            // Stands in for a context written by a release whose classes no longer match.
            jdbc.update(
                    "update spring_session_attributes set attribute_bytes = ? where attribute_name = ?"
                            + " and session_primary_id = (select primary_id from spring_session where session_id = ?)",
                    new byte[] {(byte) 0xac, (byte) 0xed, 0, 5, 1},
                    "SPRING_SECURITY_CONTEXT",
                    sessionId(cookie));

            assertThat(send(client, get("/api/auth/account", cookie)).statusCode())
                    .isEqualTo(401);
        }
    }

    private Integer idleSeconds(String cookie) {
        return jdbc.queryForObject(
                "select max_inactive_interval from spring_session where session_id = ?",
                Integer.class,
                sessionId(cookie));
    }

    private static String sessionId(String cookie) {
        return new String(Base64.getDecoder().decode(cookie.split("=", 2)[1]), StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder get(String path, String cookie) {
        var request = HttpRequest.newBuilder(uri(path)).GET();
        return cookie == null ? request : request.header("Cookie", cookie);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
