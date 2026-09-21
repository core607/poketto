package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.GitHubConnections;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.GitHubRepositoryReconnections;
import io.github.core607.poketto.content.GitHubWebhookException;
import io.github.core607.poketto.content.GitHubWebhooks;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
        properties = {
            "poketto.github.app-id=123",
            "poketto.github.client-id=Iv.fixture",
            "poketto.github.webhook-secret=fixture-webhook-secret-at-least-32-bytes"
        })
@AutoConfigureMockMvc
@Import(RemoteRepositoryIntegrationConfiguration.class)
class GitHubWebhookIntegrationIT {
    private static final String PATH = "/api/hooks/github";
    private static final String SECRET = "fixture-webhook-secret-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final GitHubAppRepositories.Owner OWNER = new GitHubAppRepositories.Owner(42, "octocat", "User");
    private static final String REVOKED = "{\"action\":\"revoked\",\"sender\":{\"id\":42}}";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {}
        registry.add("poketto.data-dir", directory::toString);
        registry.add("poketto.test.repository-path", remote::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    AuthService auth;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    GitHubWebhooks webhooks;

    private GitHubAppGrantStore grants;
    private ManagedGitHubConnections github;
    private GitHubRepositoryBindings bindings;
    private GitHubAppRepositories repositories;
    private AuthPrincipal actor;
    private WorkspaceId workspace;
    private WorkspaceId other;

    @BeforeEach
    void setup() {
        jdbc.execute(
                "truncate auth_accounts,content_github_webhook_deliveries,content_github_access_epochs,content_github_authorization_epochs cascade");
        jdbc.execute("delete from workspaces where not is_default");
        actor = AccountFixtures.create(auth, "fixture-" + UUID.randomUUID(), "fixture-account-password");
        jdbc.update("update auth_accounts set site_group='CREATOR' where account_id=?", actor.accountId());
        var cipher = new GitHubAppGrantCipher(
                new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])), "Iv.fixture");
        grants = new GitHubAppGrantStore(jdbc, cipher, "Iv.fixture", Clock.fixed(NOW, ZoneOffset.UTC));
        grants.authorize(actor.accountId(), 0, OWNER, tokens());
        workspace = bind(91, 7);
        other = bind(92, 7);
        github = connection();
        bindings = new GitHubRepositoryBindings(jdbc, github);
    }

    private ManagedGitHubConnections connection() {
        repositories = mock(GitHubAppRepositories.class);
        when(repositories.currentUser(anyString())).thenReturn(OWNER);
        when(repositories.known(anyLong(), anyLong(), anyString(), anyString()))
                .thenAnswer(call -> repository(call.getArgument(1)));
        GitHubAppInstallations installations = mock(GitHubAppInstallations.class);
        when(installations.find(any(), anyString())).thenReturn(7L);
        when(installations.issue(anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return new GitHubAppInstallations.Token(
                    "ghs_fixture", NOW.plusSeconds(3600), repository(call.getArgument(2)));
        });
        GitHubAppOAuth oauth = mock(GitHubAppOAuth.class);
        when(oauth.begin()).thenReturn(new GitHubAppOAuth.Flow("s".repeat(43), "v".repeat(43), NOW));
        when(oauth.authorization(any())).thenReturn(URI.create("https://github.com/login/oauth/authorize"));
        when(oauth.exchange(any(), anyString(), anyString())).thenReturn(tokens());
        return new ManagedGitHubConnections(
                accounts,
                grants,
                oauth,
                repositories,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC),
                installations,
                jdbc,
                mock(ManagedRepositoryConnections.class));
    }

    @Test
    void signedRevocationIsStatelessAndInvalidatesPreparedCredentialsWithoutDeletingMembership() throws Exception {
        RepositoryBinding prepared = bindings.binding(workspace);
        send(UUID.randomUUID(), "github_app_authorization", REVOKED)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(grants.find(actor.accountId()).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.REVOKED);
        assertThat(grants.find(actor.accountId()).orElseThrow().sealedTokens()).isNull();
        assertThatThrownBy(prepared::requireCurrent).hasRootCauseMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from auth_memberships where account_id=?", Integer.class, actor.accountId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from content_repository_bindings where github_revoked", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void invalidSignaturesHeadersAndTamperedBodiesDoNotRecordOrRevoke() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(REVOKED)).andExpect(status().isForbidden());
        mvc.perform(post(PATH)
                        .contentType("application/json")
                        .content(REVOKED + " ")
                        .header("X-Hub-Signature-256", signature(REVOKED.getBytes(StandardCharsets.UTF_8)))
                        .header("X-GitHub-Delivery", UUID.randomUUID())
                        .header("X-GitHub-Event", "github_app_authorization"))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH)
                        .contentType("application/json")
                        .content(REVOKED)
                        .header("X-Hub-Signature-256", signature(REVOKED.getBytes(StandardCharsets.UTF_8)), "extra")
                        .header("X-GitHub-Delivery", UUID.randomUUID())
                        .header("X-GitHub-Event", "github_app_authorization"))
                .andExpect(status().isBadRequest());
        assertThat(deliveries()).isZero();
        bindings.binding(workspace).requireCurrent();
        mvc.perform(post("/api/auth/workspaces/github/start")).andExpect(status().isForbidden());
        mvc.perform(get("/api/hooks/github/other")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "{\"action\":\"revoked\",\"sender\":{\"id\":\"42\"}}",
                "{\"action\":\"revoked\",\"sender\":{\"id\":42.2}}",
                "{\"action\":\"revoked\",\"sender\":{\"id\":42},\"action\":\"revoked\"}"
            })
    void signedMalformedPayloadsFailBeforeMutation(String body) throws Exception {
        send(UUID.randomUUID(), "github_app_authorization", body).andExpect(status().isBadRequest());
        assertThat(deliveries()).isZero();
        bindings.binding(workspace).requireCurrent();
    }

    @Test
    void replayCannotRevokeNewConsentAndPersistsAcrossHandlerRecreation() throws Exception {
        UUID delivery = UUID.randomUUID();
        send(delivery, "github_app_authorization", REVOKED).andExpect(status().isNoContent());
        grants.authorize(actor.accountId(), 2, OWNER, tokens());
        var restarted = new GitHubAppWebhooks(123, SECRET, new GitHubWebhookStore(jdbc, transactions, "Iv.fixture"));
        byte[] body = REVOKED.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () -> restarted.receive(delivery.toString(), "github_app_authorization", signature(body), body))
                .hasMessage("GitHub webhook: REPLAYED");
        assertThat(grants.find(actor.accountId()).orElseThrow().version()).isEqualTo(3);
        assertThat(grants.find(actor.accountId()).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
        assertThat(deliveries()).isOne();
    }

    @Test
    void selectedRemovalInvalidatesOnlyThatRepositoryAndPermissionAdditionsDoNotRestoreIt() throws Exception {
        RepositoryBinding first = bindings.binding(workspace);
        RepositoryBinding retained = bindings.binding(other);
        send(UUID.randomUUID(), "installation_repositories", selection("removed", "[{\"id\":91}]"))
                .andExpect(status().isNoContent());
        assertThatThrownBy(first::requireCurrent).hasRootCauseMessage("GitHub App: REPOSITORY_CHANGED");
        retained.requireCurrent();
        send(UUID.randomUUID(), "installation_repositories", selection("added", "[]"))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> bindings.binding(workspace)).hasRootCauseMessage("GitHub App: INSTALLATION_REQUIRED");
        assertThat(grants.find(actor.accountId()).orElseThrow().state()).isEqualTo(GitHubAppGrantStore.State.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "suspend"})
    void installationRevocationInvalidatesPreparedProofEvenBeforeTheBindingExists(String action) throws Exception {
        GitHubRepositoryProvisioning.PreparedBinding prepared =
                github.prepareBinding(actor, new GitHubRepositoryProvisioning.Owner(42, "octocat", 1), 91, "notes91");
        jdbc.update("delete from content_repository_bindings where workspace_id=?", workspace.value());
        send(UUID.randomUUID(), "installation", installation(action)).andExpect(status().isNoContent());
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .executeWithoutResult(status -> github.install(actor, workspace, prepared)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from content_repository_bindings where workspace_id=?",
                        Integer.class,
                        workspace.value()))
                .isZero();
    }

    @Test
    void emptyRemovalClosesTheInstallationAndRepositoryTransferInvalidatesItsIdentity() throws Exception {
        send(UUID.randomUUID(), "installation_repositories", selection("removed", "[]"))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> bindings.binding(workspace)).hasRootCauseMessage("GitHub App: INSTALLATION_REQUIRED");
        assertThatThrownBy(() -> bindings.binding(other)).hasRootCauseMessage("GitHub App: INSTALLATION_REQUIRED");
        GitHubRepositoryReconnections.Prepared proof = bindings.prepare(actor, workspace, "notes91");
        send(
                        UUID.randomUUID(),
                        "repository",
                        "{\"action\":\"transferred\",\"installation\":{\"id\":7},\"repository\":{\"id\":91,\"owner\":{\"id\":99}}}")
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .executeWithoutResult(status -> bindings.apply(actor, workspace, proof)))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
    }

    @Test
    void firstConsentCannotCommitAcrossAnAuthorizationRevocation() {
        AuthPrincipal newcomer = AccountFixtures.create(auth, "new-" + UUID.randomUUID(), "fixture-account-password");
        jdbc.update("update auth_accounts set site_group='CREATOR' where account_id=?", newcomer.accountId());
        GitHubConnections.Authorization flow = github.begin(newcomer);
        var reads = new AtomicInteger();
        when(repositories.currentUser(anyString())).thenAnswer(call -> {
            if (reads.incrementAndGet() == 2) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                byte[] body = REVOKED.getBytes(StandardCharsets.UTF_8);
                webhooks.receive(UUID.randomUUID().toString(), "github_app_authorization", signature(body), body);
            }
            return OWNER;
        });
        assertThatThrownBy(() -> github.complete(newcomer, flow, "code", () -> {}))
                .hasMessage("GitHub App: AUTHORIZATION_CHANGED");
        assertThat(grants.find(newcomer.accountId())).isEmpty();
    }

    @Test
    void refreshCannotRestoreAWebhookRevokedGrant() throws Exception {
        GitHubAppGrantStore.Grant lease = grants.claimRefresh(
                        grants.find(actor.accountId()).orElseThrow())
                .orElseThrow();
        send(UUID.randomUUID(), "github_app_authorization", REVOKED).andExpect(status().isNoContent());
        assertThatThrownBy(() -> grants.refreshed(lease, tokens())).hasMessage("GitHub App: AUTHORIZATION_CHANGED");
    }

    @Test
    void failedRevocationRollsBackTheReceiptAndCanBeRedelivered() throws Exception {
        UUID delivery = UUID.randomUUID();
        jdbc.update(
                "update content_repository_bindings set github_binding_version=? where workspace_id=?",
                Long.MAX_VALUE,
                workspace.value());
        byte[] body = installation("deleted").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> webhooks.receive(delivery.toString(), "installation", signature(body), body))
                .isInstanceOf(DataAccessException.class);
        assertThat(deliveries()).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from content_github_access_epochs where epoch>0", Integer.class))
                .isZero();
        jdbc.update(
                "update content_repository_bindings set github_binding_version=1 where workspace_id=?",
                workspace.value());
        send(delivery, "installation", installation("deleted")).andExpect(status().isNoContent());
    }

    @Test
    void wrongAppAndMissingInstallationFieldsAreRejectedWhilePingIsHarmless() throws Exception {
        send(UUID.randomUUID(), "installation", installation("deleted").replace("123", "999"))
                .andExpect(status().isBadRequest());
        send(UUID.randomUUID(), "installation", "{\"action\":\"deleted\",\"installation\":{\"id\":7}}")
                .andExpect(status().isBadRequest());
        send(UUID.randomUUID(), "ping", "{\"zen\":\"你好，世界\"}").andExpect(status().isNoContent());
        bindings.binding(workspace).requireCurrent();
        assertThat(deliveries()).isOne();
    }

    @Test
    void declaredAndUnframedOversizedBodiesAreBounded() throws Exception {
        byte[] oversized = new byte[GitHubWebhooks.MAX_BODY_BYTES + 1];
        mvc.perform(post(PATH).contentType("application/json").content(oversized))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(context -> {
                    var request = new MockHttpServletRequest(context, "POST", PATH) {
                        @Override
                        public long getContentLengthLong() {
                            return -1;
                        }
                    };
                    request.setContentType("application/json");
                    request.setContent(oversized);
                    request.addHeader("X-Hub-Signature-256", "sha256=" + "0".repeat(64));
                    request.addHeader("X-GitHub-Delivery", UUID.randomUUID());
                    request.addHeader("X-GitHub-Event", "ping");
                    return request;
                })
                .andExpect(status().isPayloadTooLarge());
        assertThat(deliveries()).isZero();
    }

    @Test
    void exactPayloadCapAcceptsTheOriginalBytes() throws Exception {
        byte[] body = new byte[GitHubWebhooks.MAX_BODY_BYTES];
        Arrays.fill(body, (byte) ' ');
        body[0] = '{';
        body[1] = '}';
        mvc.perform(post(PATH)
                        .contentType("application/json")
                        .content(body)
                        .header("X-Hub-Signature-256", signature(body))
                        .header("X-GitHub-Delivery", UUID.randomUUID())
                        .header("X-GitHub-Event", "ping"))
                .andExpect(status().isNoContent());
        assertThat(deliveries()).isOne();
    }

    @Test
    void concurrentInstancesCommitOneReceiptAndOneRevocation() throws Exception {
        byte[] body = REVOKED.getBytes(StandardCharsets.UTF_8);
        String signature = signature(body);
        String delivery = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> receive = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    webhooks.receive(delivery, "github_app_authorization", signature, body);
                    return "accepted";
                } catch (GitHubWebhookException rejected) {
                    return rejected.code().name();
                }
            };
            Future<String> first = workers.submit(receive);
            Future<String> second = workers.submit(receive);
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("accepted", "REPLAYED");
        }
        assertThat(grants.find(actor.accountId()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void revocationIsScopedToTheConfiguredAppClient() throws Exception {
        AuthPrincipal otherAccount =
                AccountFixtures.create(auth, "other-" + UUID.randomUUID(), "fixture-account-password");
        var cipher = new GitHubAppGrantCipher(
                new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])), "Iv.other");
        var otherApp = new GitHubAppGrantStore(jdbc, cipher, "Iv.other", Clock.fixed(NOW, ZoneOffset.UTC));
        otherApp.authorize(otherAccount.accountId(), 0, OWNER, tokens());
        send(UUID.randomUUID(), "github_app_authorization", REVOKED).andExpect(status().isNoContent());
        assertThat(otherApp.find(otherAccount.accountId()).orElseThrow().state())
                .isEqualTo(GitHubAppGrantStore.State.ACTIVE);
    }

    private WorkspaceId bind(long repository, long installation) {
        WorkspaceId id = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug) values (?,'Fixture',?)",
                id.value(),
                "fixture-" + repository);
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role) values (?,?,'OWNER')",
                id.value(),
                actor.accountId());
        jdbc.update(
                """
                insert into content_repository_bindings(workspace_id,canonical_uri,provider_identity,credential_kind,
                    github_account_id,github_owner_id,github_installation_id) values (?,?,?,'GITHUB_APP',?,42,?)
                """,
                id.value(),
                "https://github.com/octocat/notes" + repository,
                "github:" + repository,
                actor.accountId(),
                installation);
        return id;
    }

    private static GitHubAppRepositories.Repository repository(long id) {
        return new GitHubAppRepositories.Repository(id, "notes" + id, OWNER, true, false, false, null);
    }

    private static GitHubAppOAuth.Tokens tokens() {
        return new GitHubAppOAuth.Tokens(
                "ghu_fixture", NOW.plusSeconds(28800), "ghr_fixture", NOW.plusSeconds(15897600));
    }

    private static String installation(String action) {
        return "{\"action\":\"" + action + "\",\"installation\":{\"id\":7,\"app_id\":123,\"account\":{\"id\":42}}}";
    }

    private static String selection(String action, String removed) {
        return installation(action).replace("}}}", "}},\"repositories_removed\":" + removed + "}");
    }

    private ResultActions send(UUID delivery, String event, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post(PATH)
                .contentType("application/json")
                .content(body)
                .header("X-Hub-Signature-256", signature(body))
                .header("X-GitHub-Delivery", delivery.toString())
                .header("X-GitHub-Event", event));
    }

    private static String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private int deliveries() {
        return jdbc.queryForObject("select count(*) from content_github_webhook_deliveries", Integer.class);
    }
}
