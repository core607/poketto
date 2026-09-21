package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning;
import io.github.core607.poketto.content.GitHubRepositoryReconnections;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import({RemoteRepositoryIntegrationConfiguration.class, GitHubReconnectionHttpIntegrationIT.ProviderConfiguration.class
})
class GitHubReconnectionHttpIntegrationIT {
    private static final String PASSWORD = "fixture-account-password";

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
    AuthService auth;

    @Autowired
    GitHubRepositoryReconnections repositories;

    @Autowired
    AuthorizedRepositoryReader reader;

    private AuthPrincipal owner;
    private WorkspaceId workspace;
    private MockHttpSession session;
    private String path;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("truncate auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        String login = "owner-" + UUID.randomUUID();
        owner = auth.initializeOwner(login, PASSWORD);
        workspace = new WorkspaceId(
                jdbc.queryForObject("select workspace_id from workspaces where is_default", UUID.class));
        path = "/api/auth/workspaces/github/repositories/" + workspace.value();
        session = login(login);
        reset(repositories, reader);
        when(repositories.status(any(), any())).thenReturn(new GitHubRepositoryReconnections.Status(true, false));
        when(repositories.prepare(any(), any(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return prepared();
        });
    }

    @Test
    void repositoryRecoveryHintsNeverReachAnonymousOrUnrelatedAccounts() throws Exception {
        String repositoryPath = "/api/admin/workspaces/" + workspace.value() + "/repository/tree";
        mvc.perform(get(repositoryPath))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
        String login = "outsider-" + UUID.randomUUID();
        AuthPrincipal other = AccountFixtures.create(auth, login, PASSWORD);
        jdbc.update("update auth_accounts set site_group='ADMINISTRATOR' where account_id=?", other.accountId());
        mvc.perform(get(repositoryPath).session(login(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(reader);
    }

    @ParameterizedTest
    @EnumSource(
            value = ContentRepositoryException.Recovery.class,
            names = {"RETRY", "RECONNECT"})
    void authorizedRepositoryFailuresExposeOnlyFixedRecoveryHints(ContentRepositoryException.Recovery recovery)
            throws Exception {
        when(reader.readTree(any(), any(), any()))
                .thenThrow(new ContentRepositoryException(
                        "private-repository-diagnostic",
                        recovery,
                        new IllegalStateException("private-provider-cause")));
        String body = mvc.perform(get("/api/admin/workspaces/" + workspace.value() + "/repository/tree")
                        .session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPOSITORY_" + recovery.name()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("private-repository-diagnostic", "private-provider-cause");
    }

    @Test
    void downgradedOwnerCanReconnectAndTheCommitHoldsAuthorization() throws Exception {
        jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", owner.accountId());
        doAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    return null;
                })
                .when(repositories)
                .apply(any(), any(), any());
        mvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizingAccount").value(true));
        mvc.perform(reconnect(session)).andExpect(status().isNoContent());
        verify(repositories).apply(any(), any(), any());
    }

    @Test
    void anonymousRequestsMissingCsrfAndUnrelatedAdministratorsCannotReachTheProvider() throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/reconnect")
                        .session(session)
                        .contentType("application/json")
                        .content("{\"repositoryName\":\"notes\"}"))
                .andExpect(status().isForbidden());
        String login = "other-" + UUID.randomUUID();
        AuthPrincipal other = AccountFixtures.create(auth, login, PASSWORD);
        jdbc.update("update auth_accounts set site_group='ADMINISTRATOR' where account_id=?", other.accountId());
        MockHttpSession outsider = login(login);
        mvc.perform(get(path).session(outsider)).andExpect(status().isForbidden());
        mvc.perform(reconnect(outsider)).andExpect(status().isForbidden());
        verifyNoInteractions(repositories);
    }

    @Test
    void membershipRevocationDuringProviderIoPreventsTheCommit() throws Exception {
        when(repositories.prepare(any(), any(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            jdbc.update(
                    "update auth_memberships set suspended_at=current_timestamp where workspace_id=? and account_id=?",
                    workspace.value(),
                    owner.accountId());
            return prepared();
        });
        mvc.perform(reconnect(session)).andExpect(status().isForbidden());
        verify(repositories, never()).apply(any(), any(), any());
    }

    @Test
    void accountCredentialRevocationDuringProviderIoPreventsTheCommit() throws Exception {
        when(repositories.prepare(any(), any(), anyString())).thenAnswer(call -> {
            jdbc.update(
                    "update auth_accounts set credential_version=credential_version+1 where account_id=?",
                    owner.accountId());
            return prepared();
        });
        mvc.perform(reconnect(session)).andExpect(status().isForbidden());
        verify(repositories, never()).apply(any(), any(), any());
    }

    private GitHubRepositoryReconnections.Prepared prepared() {
        Instant now = Instant.now();
        var binding = new GitHubRepositoryProvisioning.PreparedBinding(
                owner.accountId(),
                new GitHubRepositoryProvisioning.Owner(42, "octocat", 1),
                new GitHubRepositoryProvisioning.Repository(91, 42, "octocat", "notes"),
                7,
                new GitHubRepositoryProvisioning.AccessEpochs(0, 0),
                now,
                now.plusSeconds(60));
        return new GitHubRepositoryReconnections.Prepared(workspace, 1, binding);
    }

    private MockHttpServletRequestBuilder reconnect(MockHttpSession target) throws Exception {
        return csrf(target, post(path + "/reconnect"))
                .contentType("application/json")
                .content("{\"repositoryName\":\"notes\"}");
    }

    private MockHttpSession login(String name) throws Exception {
        var target = new MockHttpSession();
        mvc.perform(csrf(target, post("/api/auth/login"))
                        .param("username", name)
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());
        return target;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpSession target, MockHttpServletRequestBuilder request)
            throws Exception {
        var token = json.readTree(mvc.perform(get("/api/auth/csrf").session(target))
                .andReturn()
                .getResponse()
                .getContentAsString());
        return request.session(target)
                .header(token.path("headerName").asString(), token.path("token").asString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        AuthorizedRepositoryReader fixtureRepositoryReader() {
            return mock(AuthorizedRepositoryReader.class);
        }

        @Bean
        @Primary
        GitHubRepositoryReconnections fixtureReconnections() {
            return mock(GitHubRepositoryReconnections.class);
        }
    }
}
