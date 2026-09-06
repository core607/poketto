package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.workspace.WorkspaceCatalog;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poketto.security.allowed-origins=https://site.example.invalid")
@AutoConfigureMockMvc
@Import({
    io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration.class,
    BrowserSecurityIntegrationIT.BodyObservation.class
})
class BrowserSecurityIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    private static final String INITIALIZATION_TOKEN = secret();
    private static final String ORIGIN = "https://site.example.invalid";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthService auth;

    @Autowired
    WorkspaceCatalog workspaces;

    @LocalServerPort
    int port;

    private final Map<Socket, String> pendingIds = new java.util.IdentityHashMap<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", () -> directory.toString());
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            // Synthetic repository for application startup, independent of authentication operations.
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        registry.add("poketto.test.repository-path", remote::toString);
        registry.add("poketto.auth.initialization-token", () -> INITIALIZATION_TOKEN);
    }

    @BeforeEach
    void resetIdentities() {
        BodyObservation.accesses.clear();
        jdbc.execute("truncate table auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at = null");
    }

    @Test
    void bootstrapLoginFixationProtectionCsrfAndLogoutUseRealSession() throws Exception {
        Csrf session = csrf(null);
        String password = secret();
        Map<String, String> body =
                Map.of("initializationToken", INITIALIZATION_TOKEN, "login", "owner", "password", password);
        mvc.perform(post("/api/auth/initialize")
                        .session(session.session())
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        mvc.perform(request(post("/api/auth/initialize"), session, body)).andExpect(status().isCreated());
        mvc.perform(request(post("/api/auth/initialize"), session, body)).andExpect(status().isConflict());
        mvc.perform(get("/api/auth/me").session(session.session())).andExpect(status().isUnauthorized());
        String priorId = session.session().getId();
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header("Origin", ORIGIN)
                        .param("username", "owner")
                        .param("password", password))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header("Origin", ORIGIN)
                        .header(session.header(), session.token())
                        .param("username", "owner")
                        .param("password", password))
                .andExpect(status().isNoContent());
        assertThat(session.session().getId()).isNotEqualTo(priorId);
        mvc.perform(get("/api/auth/me").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(post("/api/admin/invitations").session(session.session()).header(session.header(), session.token()))
                .andExpect(status().isForbidden());
        Csrf loggedIn = csrf(session.session());
        mvc.perform(get("/api/auth/logout").session(loggedIn.session())).andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/logout").session(loggedIn.session())).andExpect(status().isForbidden());
        mvc.perform(request(post("/api/auth/logout"), loggedIn, null)).andExpect(status().isNoContent());
        assertThat(loggedIn.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invitationRegistrationAndMembershipSuspensionInvalidateExistingRequests() throws Exception {
        String ownerPassword = secret();
        AuthPrincipal owner = auth.initializeOwner(INITIALIZATION_TOKEN, "invite-owner", ownerPassword);
        Csrf ownerSession = login("invite-owner", ownerPassword);
        JsonNode invitation = body(mvc.perform(request(post("/api/admin/invitations"), ownerSession, null))
                .andExpect(status().isCreated())
                .andReturn());
        String token = invitation.get("token").stringValue();
        Csrf guest = csrf(null);
        String memberPassword = secret();
        JsonNode member = body(mvc.perform(request(
                        post("/api/auth/invitations/register"),
                        guest,
                        Map.of("token", token, "login", "joined-member", "password", memberPassword)))
                .andExpect(status().isCreated())
                .andReturn());
        mvc.perform(request(
                        post("/api/auth/invitations/register"),
                        guest,
                        Map.of("token", token, "login", "another-member", "password", secret())))
                .andExpect(status().isBadRequest());
        Csrf memberSession = login("joined-member", memberPassword);
        mvc.perform(get("/api/auth/me").session(memberSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));
        mvc.perform(get("/api/admin/members").session(memberSession.session())).andExpect(status().isForbidden());
        mvc.perform(request(post("/api/auth/invitations/accept"), memberSession, Map.of("token", token)))
                .andExpect(status().isOk());
        mvc.perform(request(
                        put("/api/admin/members/" + member.get("accountId").stringValue()),
                        ownerSession,
                        Map.of("role", "MEMBER", "active", false)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(memberSession.session())).andExpect(status().isForbidden());
        mvc.perform(request(
                        put("/api/admin/members/" + owner.accountId()),
                        ownerSession,
                        Map.of("role", "OWNER", "active", false)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/admin/invitations").session(ownerSession.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    }

    @Test
    void bearerMcpIsStatelessSeparateFromBrowserAndRevalidatesRevocation() throws Exception {
        String password = secret();
        AuthPrincipal owner = auth.initializeOwner(INITIALIZATION_TOKEN, "key-owner", password);
        Csrf session = login("key-owner", password);
        JsonNode key =
                body(mvc.perform(request(post("/api/admin/keys"), session, Map.of("accountId", owner.accountId())))
                        .andExpect(status().isCreated())
                        .andReturn());
        String bearer = "Bearer " + key.get("token").stringValue();
        mvc.perform(get("/api/auth/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").session(session.session())).andExpect(status().isUnauthorized());
        MvcResult accepted = mvc.perform(post("/mcp").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(accepted.getRequest().getSession(false)).isNull();
        mvc.perform(post("/mcp").header("Authorization", bearer).header("Origin", "https://unexpected.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/keys").session(session.session()))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                key.get("token").stringValue()))));
        mvc.perform(request(delete("/api/admin/keys/" + key.get("id").stringValue()), session, null))
                .andExpect(status().isNoContent());
        mvc.perform(post("/mcp").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(session.session())).andExpect(status().isOk());
    }

    @Test
    void originsBoundsAndCredentialsFailClosedWithoutRedirectsOrSecretEchoes() throws Exception {
        Csrf session = csrf(null);
        mvc.perform(get("/api/auth/csrf").header("Origin", "null")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").header("Origin", ORIGIN, "https://unexpected.invalid"))
                .andExpect(status().isForbidden());
        String unknown = secret();
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header(session.header(), session.token())
                        .param("username", "unknown-user")
                        .param("password", unknown))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(unknown))));
        mvc.perform(post("/api/auth/initialize")
                        .session(session.session())
                        .header(session.header(), session.token())
                        .contentType("application/json")
                        .content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(request(
                        post("/api/auth/initialize"),
                        session,
                        Map.of("initializationToken", unknown, "login", "unknown-owner", "password", secret())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(unknown))));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void liveServletIssuesSecureHttpOnlyStrictCookie() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("set-cookie")).anySatisfy(cookie -> {
            assertThat(cookie).startsWith("POKETTO_SESSION=").contains("Secure", "HttpOnly", "SameSite=Strict");
        });
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
    }

    @Test
    void liveChunkedInitializationRejectsMaxPlusOneBeforeCreatingOwner() throws Exception {
        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        JsonNode token = json.readTree(csrf.body());
        String cookie = csrf.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String payload = json.writeValueAsString(
                Map.of("initializationToken", INITIALIZATION_TOKEN, "login", "chunk-owner", "password", secret()));
        int padding = 16384 - payload.getBytes(StandardCharsets.UTF_8).length;
        for (String overLimit :
                java.util.List.of(payload + " ".repeat(padding + 1), " ".repeat(padding + 1) + payload)) {
            byte[] bytes = overLimit.getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> rejected = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/initialize"))
                            .header("Origin", ORIGIN)
                            .header("Cookie", cookie)
                            .header(
                                    token.get("headerName").stringValue(),
                                    token.get("token").stringValue())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "select count(*) from auth_initialization where initialized_at is not null", Integer.class))
                    .isZero();
            assertThat(rejected.statusCode())
                    .as("chunked body with %s bytes", bytes.length)
                    .isEqualTo(413);
            assertThat(rejected.body()).doesNotContain(INITIALIZATION_TOKEN, "chunk-owner");
        }
        byte[] exact = (" ".repeat(padding) + payload).getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> accepted = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/initialize"))
                        .header("Origin", ORIGIN)
                        .header("Cookie", cookie)
                        .header(
                                token.get("headerName").stringValue(),
                                token.get("token").stringValue())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(exact)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void liveChunkedFormRejectsOverflowWithoutLoggingInAndAcceptsExactLimit() throws Exception {
        String password = secret();
        auth.initializeOwner(INITIALIZATION_TOKEN, "form-owner", password);
        HttpClient client =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        JsonNode token = json.readTree(csrf.body());
        String cookie = csrf.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String form = "username=form-owner&password=" + password + "&padding=";
        int padding = 16384 - form.getBytes(StandardCharsets.UTF_8).length;
        for (int size : new int[] {16385, 16384}) {
            byte[] bytes = (form + "x".repeat(padding + size - 16384)).getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                            .header("Origin", ORIGIN)
                            .header("Cookie", cookie)
                            .header(
                                    token.get("headerName").stringValue(),
                                    token.get("token").stringValue())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("chunked login body size %s", bytes.length)
                    .isEqualTo(size == 16385 ? 413 : 204);
            assertThat(response.body()).doesNotContain(password, "form-owner", "padding");
            if (size == 16385) {
                assertThat(response.headers().allValues("set-cookie")).isEmpty();
                HttpResponse<String> me = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(me.statusCode()).isEqualTo(401);
                assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                        .isEqualTo(1);
            } else {
                assertThat(response.headers().allValues("set-cookie"))
                        .anyMatch(value -> !value.startsWith(cookie + ";"));
            }
        }
    }

    @Test
    void repeatedHttpLoginAttemptsAreThrottled() throws Exception {
        Csrf session = csrf(null);
        String password = secret();
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login")
                            .session(session.session())
                            .header(session.header(), session.token())
                            .param("username", "throttled-user")
                            .param("password", password))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                        .session(session.session())
                        .header(session.header(), session.token())
                        .param("username", "throttled-user")
                        .param("password", password))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"));
        for (String variant : java.util.List.of(" throttled-user", "throttled-user ", "\tTHROTTLED-USER\t")) {
            mvc.perform(post("/api/auth/login")
                            .session(session.session())
                            .header(session.header(), session.token())
                            .param("username", variant)
                            .param("password", password))
                    .andExpect(status().isTooManyRequests());
        }
    }

    @Test
    void liveAnonymousAdminRequestsRejectWithoutRequestBodyAccessForAnyFraming() throws Exception {
        for (String method : new String[] {"GET", "POST"}) {
            for (boolean declared : new boolean[] {false, true}) {
                for (String type : new String[] {"application/json", "multipart/form-data; boundary=synthetic"}) {
                    try (Socket request = pendingBody(method, "/api/admin/assets", type, declared, null)) {
                        assertThat(readStatus(request)).isEqualTo(401);
                        assertThat(BodyObservation.accesses.get(pendingIds.get(request)))
                                .hasValue(0);
                    }
                }
            }
        }
    }

    @Test
    void liveSlowBodiesSaturateAfterLoginAndDisconnectReturnsAdmission() throws Exception {
        String password = secret();
        auth.initializeOwner(INITIALIZATION_TOKEN, "slow-owner", password);
        LiveSession session = liveLogin("slow-owner", password);
        try (Socket first = pendingBody("POST", "/api/admin/repository/preview", "application/json", false, session);
                Socket second = pendingBody(
                        "POST", "/api/admin/assets", "multipart/form-data; boundary=synthetic", true, session)) {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(BodyObservation.accesses.get(pendingIds.get(first)).get())
                        .isPositive();
                assertThat(BodyObservation.accesses.get(pendingIds.get(second)).get())
                        .isPositive();
            });
            try (Socket overflow =
                    pendingBody("POST", "/api/admin/repository/preview", "application/json", true, session)) {
                assertThat(readStatus(overflow)).isEqualTo(429);
                assertThat(BodyObservation.accesses.get(pendingIds.get(overflow)))
                        .hasValue(0);
            }
            first.close();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(
                                                "http://localhost:" + port + "/api/admin/repository/preview"))
                                        .timeout(Duration.ofSeconds(5))
                                        .header("Cookie", session.cookie())
                                        .header(session.header(), session.token())
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                // Missing preview fields are checked by the real controller, after successful admission.
                assertThat(response.statusCode()).isEqualTo(400);
            });
        }
    }

    private LiveSession liveLogin(String login, String password) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            HttpResponse<String> csrf = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(csrf.statusCode()).isEqualTo(200);
            JsonNode token = json.readTree(csrf.body());
            String cookie =
                    csrf.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            HttpResponse<String> signedIn = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                            .header("Cookie", cookie)
                            .header(
                                    token.get("headerName").stringValue(),
                                    token.get("token").stringValue())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("username=" + login + "&password=" + password))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(signedIn.statusCode()).isEqualTo(204);
            String rotated =
                    signedIn.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            HttpResponse<String> current = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
                            .header("Cookie", rotated)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(current.statusCode()).isEqualTo(200);
            JsonNode fresh = json.readTree(current.body());
            return new LiveSession(
                    rotated,
                    fresh.get("headerName").stringValue(),
                    fresh.get("token").stringValue());
        }
    }

    private Socket pendingBody(String method, String path, String type, boolean declared, LiveSession session)
            throws Exception {
        var socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(5000);
            String id = UUID.randomUUID().toString();
            pendingIds.put(socket, id);
            BodyObservation.accesses.put(id, new AtomicInteger());
            String headers = method + " " + path + " HTTP/1.1\r\nHost: localhost:" + port
                    + "\r\nConnection: close\r\nExpect: 100-continue\r\nX-Synthetic-Read-Id: " + id
                    + "\r\nContent-Type: " + type + "\r\n"
                    + (declared ? "Content-Length: 1048576\r\n" : "Transfer-Encoding: chunked\r\n")
                    + (session == null
                            ? ""
                            : "Cookie: " + session.cookie() + "\r\n" + session.header() + ": " + session.token()
                                    + "\r\n")
                    + "\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return socket;
        } catch (Exception exception) {
            socket.close();
            throw exception;
        }
    }

    private int readStatus(Socket socket) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        for (; ; ) {
            String line = reader.readLine();
            assertThat(line).isNotNull();
            int status = Integer.parseInt(line.split(" ", 3)[1]);
            while ((line = reader.readLine()) != null && !line.isEmpty()) {}
            if (status != 100) return status;
        }
    }

    @TestConfiguration
    static class BodyObservation {
        static final Map<String, AtomicInteger> accesses = new ConcurrentHashMap<>();

        @Bean
        FilterRegistrationBean<Filter> bodyReads() {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                var http = (HttpServletRequest) request;
                String id = http.getHeader("X-Synthetic-Read-Id");
                AtomicInteger observed = id == null ? null : accesses.get(id);
                if (observed == null) {
                    chain.doFilter(request, response);
                    return;
                }
                chain.doFilter(
                        new HttpServletRequestWrapper(http) {
                            @Override
                            public ServletInputStream getInputStream() throws java.io.IOException {
                                observed.incrementAndGet();
                                return super.getInputStream();
                            }

                            @Override
                            public Collection<Part> getParts()
                                    throws java.io.IOException, jakarta.servlet.ServletException {
                                observed.incrementAndGet();
                                return super.getParts();
                            }

                            @Override
                            public String getParameter(String name) {
                                observed.incrementAndGet();
                                return super.getParameter(name);
                            }
                        },
                        response);
            });
            registration.setOrder(-101);
            return registration;
        }
    }

    private record LiveSession(String cookie, String header, String token) {
        @Override
        public String toString() {
            return "LiveSession[REDACTED]";
        }
    }

    private Csrf login(String login, String password) throws Exception {
        Csrf guest = csrf(null);
        mvc.perform(post("/api/auth/login")
                        .session(guest.session())
                        .header("Origin", ORIGIN)
                        .header(guest.header(), guest.token())
                        .param("username", login)
                        .param("password", password))
                .andExpect(status().isNoContent());
        return csrf(guest.session());
    }

    private Csrf csrf(MockHttpSession existing) throws Exception {
        var request = get("/api/auth/csrf");
        if (existing != null) request.session(existing);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode response = body(result);
        return new Csrf(
                (MockHttpSession) result.getRequest().getSession(false),
                response.get("headerName").stringValue(),
                response.get("token").stringValue());
    }

    private MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder request, Csrf session, Object body) {
        request.session(session.session()).header("Origin", ORIGIN).header(session.header(), session.token());
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return request;
    }

    private JsonNode body(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private record Csrf(MockHttpSession session, String header, String token) {
        @Override
        public String toString() {
            return "Csrf[REDACTED]";
        }
    }

    private static String secret() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }
}
