package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.RegistrationService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(WorkspaceEntrancesIntegrationIT.Repositories.class)
class WorkspaceEntrancesIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    static final Map<WorkspaceId, Path> remotes = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) throws Exception {
        directory = directory.toRealPath();
        for (String name : new String[] {"first.git", "second.git"}) {
            try (Git ignored = Git.init()
                    .setBare(true)
                    .setInitialBranch("main")
                    .setDirectory(directory.resolve(name).toFile())
                    .call()) {}
        }
        properties.add("poketto.data-dir", directory::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AuthService auth;

    @Autowired
    RegistrationService registration;

    @Autowired
    WorkspaceCatalog catalog;

    @Autowired
    WorkspaceRegistry registry;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void independentTabRoutesWriteDifferentGitAuthoritiesAndForeignMembershipCannotReadEitherEntrance()
            throws Exception {
        AuthPrincipal owner = auth.initializeOwner("space-owner", "fixture-password-1234");
        WorkspaceId first = catalog.defaultWorkspace().id();
        remotes.put(first, directory.resolve("first.git"));
        WorkspaceId second = WorkspaceId.random();
        remotes.put(second, directory.resolve("second.git"));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            registry.create(second, "Second notes", "second-notes");
            auth.establishWorkspaceOwner(owner, second);
        });
        AuthPrincipal guest =
                registration.register(registration.issue(owner).token(), "second-reader", "fixture-password-5678");
        auth.acceptInvitation(guest, auth.createInvitation(owner, second).token());
        var ownerSession = login("space-owner", "fixture-password-1234");
        var guestSession = login("second-reader", "fixture-password-5678");
        mvc.perform(get("/api/auth/workspaces").session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].workspaceId").value(second.toString()));
        mvc.perform(get("/api/auth/workspaces/" + first + "/me").session(guestSession))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/repository/tree").session(ownerSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/workspaces/not-a-uuid/repository/tree").session(ownerSession))
                .andExpect(status().isBadRequest());
        String[] denied = {
            "repository/tree",
            "repository/directory",
            "repository/search",
            "members",
            "keys",
            "assets",
            "exports/unknown/metadata"
        };
        for (String operation : denied)
            mvc.perform(get(route(first, operation)).session(guestSession)).andExpect(status().isForbidden());
        var firstWrite = csrf(ownerSession, post(route(first, "repository/patch")))
                .contentType("application/json")
                .content(patch("# First workspace\n"));
        var secondWrite = csrf(ownerSession, post(route(second, "repository/patch")))
                .contentType("application/json")
                .content(patch("# Second workspace\n"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(
                    () -> mvc.perform(firstWrite).andExpect(status().isOk()).andReturn());
            var b = pool.submit(
                    () -> mvc.perform(secondWrite).andExpect(status().isOk()).andReturn());
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }
        for (var entry : Map.of(first, "# First workspace\n", second, "# Second workspace\n")
                .entrySet()) {
            mvc.perform(get(route(entry.getKey(), "repository/file"))
                            .param("path", "private/shared.md")
                            .session(ownerSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source").value(entry.getValue()));
            try (var git = Git.open(remotes.get(entry.getKey()).toFile());
                    var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository());
                    var tree = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                            git.getRepository(),
                            "private/shared.md",
                            walk.parseCommit(git.getRepository().resolve("refs/heads/main"))
                                    .getTree())) {
                assertThat(new String(
                                git.getRepository()
                                        .open(tree.getObjectId(0), Constants.OBJ_BLOB)
                                        .getBytes(),
                                java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo(entry.getValue());
            }
        }
        var firstKey = auth.createApiKey(owner, first, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        var secondKey = auth.createApiKey(owner, second, owner.accountId(), Set.of(Capability.READ_PRIVATE));
        assertThat(auth.workspaceForKey(auth.authenticateApiKey(firstKey.token())))
                .isEqualTo(first);
        assertThat(auth.workspaceForKey(auth.authenticateApiKey(secondKey.token())))
                .isEqualTo(second);
        auth.revokeApiKey(owner, second, secondKey.id());
        assertThat(auth.workspaceForKey(auth.authenticateApiKey(firstKey.token())))
                .isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "select public_delivery from workspaces where workspace_id=?", Boolean.class, second.value()))
                .isFalse();
    }

    private String patch(String source) throws Exception {
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("baseCommit", null);
        request.put(
                "changes",
                java.util.List.of(Map.of("path", "private/shared.md", "expectedAbsence", true, "content", source)));
        return json.writeValueAsString(request);
    }

    private MockHttpSession login(String name, String password) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", name)
                        .param("password", password))
                .andExpect(status().isNoContent());
        return session;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpSession session, MockHttpServletRequestBuilder request)
            throws Exception {
        var token = json.readTree(mvc.perform(get("/api/auth/csrf").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString());
        return request.session(session)
                .header(token.path("headerName").asString(), token.path("token").asString());
    }

    private static String route(WorkspaceId workspace, String operation) {
        return "/api/admin/workspaces/" + workspace + "/" + operation;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Repositories {
        @Bean
        @Primary
        RepositoryBindingSource isolatedAuthorities() {
            return workspace -> {
                try {
                    return new RepositoryBinding(
                            new URIish(remotes.getOrDefault(workspace, directory.resolve("first.git"))
                                    .toUri()
                                    .toString()),
                            new UsernamePasswordCredentialsProvider("fixture", "fixture"));
                } catch (java.net.URISyntaxException exception) {
                    throw new IllegalStateException(exception);
                }
            };
        }
    }
}
