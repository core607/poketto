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

    @Autowired
    io.github.core607.poketto.content.RepositoryMoveService moves;

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
        auth.acceptInvitation(
                guest, auth.createInvitation(owner, second, Set.of()).token());
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

        String previous = json.readTree(
                        mvc.perform(get(route(second, "repository/tree")).session(ownerSession))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("commit")
                .asString();
        var content = Map.of(
                ".poketto/publishing.yaml", "enabled: true\nmode: public-root\nexclude:\n  - public/excluded/**\n",
                "public/a.md", "# Allowed A\nVisible source\n",
                "public/b.md", "# Allowed B\nVisible source [A](a.md)\n",
                "public/excluded/secret.md", "# ExcludedSecret\n",
                "public/.hidden/secret.md", "# HiddenSecret\n",
                "public/AGENTS.md", "# GuideSecret\n");
        var seeded = mvc.perform(csrf(ownerSession, post(route(second, "repository/patch")))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "baseCommit",
                                previous,
                                "changes",
                                content.entrySet().stream()
                                        .map(entry -> Map.of(
                                                "path",
                                                entry.getKey(),
                                                "expectedAbsence",
                                                true,
                                                "content",
                                                entry.getValue()))
                                        .toList()))))
                .andExpect(status().isOk())
                .andReturn();
        String current = json.readTree(seeded.getResponse().getContentAsString())
                .path("commit")
                .asString();
        mvc.perform(get(route(second, "repository/tree")).session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].path").value("public/a.md"));
        mvc.perform(get(route(second, "repository/directory")).session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].path").value("public"));
        mvc.perform(get(route(second, "repository/directory"))
                        .param("path", "public")
                        .param("limit", "1")
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value("public/a.md"))
                .andExpect(jsonPath("$.nextOffset").value(1));
        mvc.perform(get(route(second, "repository/directory"))
                        .param("path", "public")
                        .param("limit", "1")
                        .param("offset", "1")
                        .param("commit", current)
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value("public/b.md"));
        mvc.perform(get(route(second, "repository/file"))
                        .param("path", "public/a.md")
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(content.get("public/a.md")))
                .andExpect(jsonPath("$.publicScope").value(true));
        for (String path : new String[] {
            "private/shared.md",
            "public/excluded/secret.md",
            "public/.hidden/secret.md",
            "public/AGENTS.md",
            ".poketto/publishing.yaml"
        })
            mvc.perform(get(route(second, "repository/file"))
                            .param("path", path)
                            .session(guestSession))
                    .andExpect(status().isForbidden());
        mvc.perform(get(route(second, "repository/search"))
                        .param("query", "Secret")
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(route(second, "repository/search"))
                        .param("query", "Visible")
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(route(second, "repository/tree"))
                        .param("commit", previous)
                        .session(guestSession))
                .andExpect(status().isForbidden());
        var publicFile = json.readTree(mvc.perform(get(route(second, "repository/file"))
                        .param("path", "public/a.md")
                        .session(guestSession))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var publicEdit = Map.of(
                "baseCommit",
                current,
                "changes",
                java.util.List.of(Map.of(
                        "path",
                        "public/a.md",
                        "expectedAbsence",
                        false,
                        "expectedRevision",
                        publicFile.path("revision").asString(),
                        "content",
                        "# Updated public source\n")));
        mvc.perform(csrf(guestSession, post(route(second, "repository/patch")))
                        .contentType("application/json")
                        .content(json.writeValueAsString(publicEdit)))
                .andExpect(status().isForbidden());
        auth.changeMembership(
                owner,
                second,
                guest.accountId(),
                io.github.core607.poketto.auth.MembershipRole.MEMBER,
                true,
                Set.of(Capability.PUBLISH));
        var publishedEdit = mvc.perform(csrf(guestSession, post(route(second, "repository/patch")))
                        .contentType("application/json")
                        .content(json.writeValueAsString(publicEdit)))
                .andExpect(status().isOk())
                .andReturn();
        String updated = json.readTree(publishedEdit.getResponse().getContentAsString())
                .path("commit")
                .asString();
        for (String deniedPath :
                new String[] {"private/new.md", "public/excluded/new.md", ".poketto/publishing.yaml"}) {
            var deniedEdit = Map.of(
                    "baseCommit",
                    updated,
                    "changes",
                    java.util.List.of(Map.of(
                            "path",
                            deniedPath,
                            "expectedAbsence",
                            true,
                            "content",
                            deniedPath.endsWith(".yaml")
                                    ? "enabled: false\nmode: public-root\n"
                                    : "# Must remain absent\n")));
            mvc.perform(csrf(guestSession, post(route(second, "repository/patch")))
                            .contentType("application/json")
                            .content(json.writeValueAsString(deniedEdit)))
                    .andExpect(status().isForbidden());
        }
        try (var git = Git.open(remotes.get(second).toFile());
                var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository());
                var fileTree = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                        git.getRepository(),
                        "public/a.md",
                        walk.parseCommit(git.getRepository().resolve("refs/heads/main"))
                                .getTree())) {
            assertThat(git.getRepository().resolve("refs/heads/main").name()).isEqualTo(updated);
            assertThat(new String(
                            git.getRepository()
                                    .open(fileTree.getObjectId(0), Constants.OBJ_BLOB)
                                    .getBytes(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("# Updated public source\n");
        }
        var plan = moves.plan(
                guest,
                second,
                new io.github.core607.poketto.content.RepositoryMoveRequest(updated, "public/a.md", "public/moved.md"));
        assertThat(plan.originals().keySet()).allMatch(path -> path.startsWith("public/"));
        var moved = mvc.perform(csrf(guestSession, post(route(second, "repository/move")))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "baseCommit", updated, "source", "public/a.md", "destination", "public/moved.md"))))
                .andExpect(status().isOk())
                .andReturn();
        String movedCommit = json.readTree(moved.getResponse().getContentAsString())
                .path("commit")
                .asString();
        mvc.perform(get(route(second, "repository/file"))
                        .param("path", "public/b.md")
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("# Allowed B\nVisible source [A](moved.md)\n"));
        mvc.perform(csrf(guestSession, post(route(second, "repository/move")))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "baseCommit", movedCommit, "source", "public/b.md", "destination", "private/b.md"))))
                .andExpect(status().isForbidden());
        var backlink = mvc.perform(csrf(ownerSession, post(route(second, "repository/patch")))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "baseCommit",
                                movedCommit,
                                "changes",
                                java.util.List.of(Map.of(
                                        "path",
                                        "private/backlink.md",
                                        "expectedAbsence",
                                        true,
                                        "content",
                                        "# Private backlink\n[Public](../public/moved.md)\n"))))))
                .andExpect(status().isOk())
                .andReturn();
        String linkedCommit = json.readTree(backlink.getResponse().getContentAsString())
                .path("commit")
                .asString();
        var linkedMove = new io.github.core607.poketto.content.RepositoryMoveRequest(
                linkedCommit, "public/moved.md", "public/final.md");
        assertThatThrownBy(() -> moves.plan(guest, second, linkedMove))
                .isInstanceOf(io.github.core607.poketto.auth.AuthException.class);
        auth.changeMembership(
                owner,
                second,
                guest.accountId(),
                io.github.core607.poketto.auth.MembershipRole.MEMBER,
                true,
                Set.of(Capability.READ_PRIVATE, Capability.PUBLISH));
        var moveBody = json.writeValueAsString(
                Map.of("baseCommit", linkedCommit, "source", "public/moved.md", "destination", "public/final.md"));
        mvc.perform(csrf(guestSession, post(route(second, "repository/move")))
                        .contentType("application/json")
                        .content(moveBody))
                .andExpect(status().isForbidden());
        try (var git = Git.open(remotes.get(second).toFile())) {
            assertThat(git.getRepository().resolve("refs/heads/main").name()).isEqualTo(linkedCommit);
        }
        auth.changeMembership(
                owner,
                second,
                guest.accountId(),
                io.github.core607.poketto.auth.MembershipRole.MEMBER,
                true,
                Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE, Capability.PUBLISH));
        mvc.perform(csrf(guestSession, post(route(second, "repository/move")))
                        .contentType("application/json")
                        .content(moveBody))
                .andExpect(status().isOk());
        mvc.perform(get(route(second, "repository/file"))
                        .param("path", "private/backlink.md")
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("# Private backlink\n[Public](../public/final.md)\n"));
        auth.changeMembership(
                owner,
                second,
                guest.accountId(),
                io.github.core607.poketto.auth.MembershipRole.MEMBER,
                true,
                Set.of(Capability.READ_PRIVATE));
        mvc.perform(get(route(second, "repository/file"))
                        .param("path", "private/shared.md")
                        .param("commit", previous)
                        .session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("# Second workspace\n"))
                .andExpect(jsonPath("$.publicScope").value(false));
        auth.changeMembership(
                owner,
                second,
                guest.accountId(),
                io.github.core607.poketto.auth.MembershipRole.MEMBER,
                false,
                Set.of());
        mvc.perform(get(route(second, "repository/tree")).session(guestSession)).andExpect(status().isForbidden());
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
