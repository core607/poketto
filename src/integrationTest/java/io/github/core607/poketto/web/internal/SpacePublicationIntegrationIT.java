package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.RegistrationService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration;
import io.github.core607.poketto.spaces.SpacePublicationService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
@Import(RemoteRepositoryIntegrationConfiguration.class)
class SpacePublicationIntegrationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) throws Exception {
        directory = directory.toRealPath();
        Path remote = directory.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {}
        seed(remote);
        properties.add("poketto.data-dir", directory::toString);
        properties.add("poketto.test.repository-path", remote::toString);
    }

    private static void seed(Path remote) throws Exception {
        Path root = directory.resolve("seed");
        try (Git git =
                Git.init().setInitialBranch("main").setDirectory(root.toFile()).call()) {
            Files.createDirectories(root.resolve(".poketto"));
            Files.createDirectories(root.resolve("public"));
            Files.createDirectories(root.resolve("private"));
            Files.writeString(root.resolve(".poketto/publishing.yaml"), "enabled: true\nmode: public-root\n");
            Files.writeString(root.resolve("public/note.md"), "# Visible\n\n![Picture](picture.png)\n");
            Files.writeString(root.resolve("private/secret.md"), "# Private sentinel\n");
            ImageIO.write(
                    new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                    "png",
                    root.resolve("public/picture.png").toFile());
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Fixture", "fixture@example.invalid")
                    .setMessage("Seed publication")
                    .call();
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(remote.toUri().toString()))
                    .call();
            git.push().setRemote("origin").setPushAll().call();
        }
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
    WorkspacePublications publications;

    @Autowired
    SpacePublicationService service;

    @Autowired
    PublicContentSnapshots snapshots;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void ownerWithdrawalClosesCachedPublicImagesWithoutRemovingMemberAccess() throws Exception {
        String password = UUID.randomUUID().toString();
        AuthPrincipal owner = auth.initializeOwner("publisher", password);
        WorkspaceId workspace = catalog.defaultWorkspace().id();
        MockHttpSession ownerSession = login("publisher", password);
        snapshots.refresh(workspace);
        mvc.perform(get(publication(workspace)).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.slug").value("home"));
        String image = publicImage();
        mvc.perform(get(image)).andExpect(status().isOk());
        mvc.perform(put(publication(workspace))
                        .session(ownerSession)
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(csrf(ownerSession, put(publication(workspace)))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        update(ownerSession, workspace, false);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/public/documents")).andExpect(status().isServiceUnavailable());
        mvc.perform(get(image)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/admin/workspaces/" + workspace + "/repository/file")
                        .session(ownerSession)
                        .param("path", "private/secret.md"))
                .andExpect(status().isOk());
        verifyMember(owner, workspace, password);
        verifyCatalog(owner, workspace);
        update(ownerSession, workspace, true);
        mvc.perform(get("/api/public/documents")).andExpect(status().isOk());
        mvc.perform(get(publicImage())).andExpect(status().isOk());
        mvc.perform(get(publication(workspace))).andExpect(status().isUnauthorized());
    }

    private void verifyMember(AuthPrincipal owner, WorkspaceId workspace, String password) throws Exception {
        AuthPrincipal member = registration.register(registration.issue(owner).token(), "reader", password);
        auth.acceptInvitation(
                member,
                auth.createInvitation(owner, workspace, Set.of(Capability.PUBLISH))
                        .token());
        MockHttpSession session = login("reader", password);
        mvc.perform(get("/api/admin/workspaces/" + workspace + "/repository/file")
                        .session(session)
                        .param("path", "public/note.md"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/workspaces/" + workspace + "/repository/file")
                        .session(session)
                        .param("path", "private/secret.md"))
                .andExpect(status().isForbidden());
        mvc.perform(get(publication(workspace)).session(session)).andExpect(status().isForbidden());
        mvc.perform(csrf(session, put(publication(workspace)))
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        String token = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.MANAGE_KEYS))
                .token();
        assertThatThrownBy(() -> service.setEnabled(auth.authenticateApiKey(token), workspace, true))
                .isInstanceOf(AuthException.class);
    }

    private void verifyCatalog(AuthPrincipal owner, WorkspaceId first) {
        WorkspaceId second = WorkspaceId.random();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            registry.create(second, "Second site", "second-site");
            auth.establishWorkspaceOwner(owner, second);
        });
        assertThat(publications.settings(second).enabled()).isFalse();
        assertThat(publications.findPublished("second-site")).isEmpty();
        assertThat(publications.findPublished("../second-site")).isEmpty();
        assertThatThrownBy(() -> publications.setEnabled(second, true)).isInstanceOf(IllegalStateException.class);
        service.setEnabled(owner, second, true);
        assertThat(publications.findPublished("home")).isEmpty();
        assertThat(publications.findPublished("second-site").orElseThrow().workspaceId())
                .isEqualTo(second);
        assertThat(publications.publishedAfter(Optional.empty(), 1))
                .extracting(WorkspacePublications.Publication::workspaceId)
                .containsExactly(second);
        assertThat(publications.publishedAfter(Optional.of(second), 1)).isEmpty();
        assertThat(publications.settings(first).enabled()).isFalse();
    }

    private String publicImage() throws Exception {
        var result = mvc.perform(get("/api/public/document").param("route", "/note"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .get("images")
                .get("picture.png")
                .stringValue();
    }

    private void update(MockHttpSession session, WorkspaceId workspace, boolean enabled) throws Exception {
        mvc.perform(csrf(session, put(publication(workspace)))
                        .contentType("application/json")
                        .content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(enabled));
    }

    private MockHttpSession login(String name, String password) throws Exception {
        var result =
                mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession();
        mvc.perform(csrf(session, post("/api/auth/login"))
                        .param("username", name)
                        .param("password", password))
                .andExpect(status().isNoContent());
        return session;
    }

    private MockHttpServletRequestBuilder csrf(MockHttpSession session, MockHttpServletRequestBuilder request)
            throws Exception {
        var result = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        var value = json.readTree(result.getResponse().getContentAsString());
        return request.session(session)
                .header(
                        value.get("headerName").stringValue(),
                        value.get("token").stringValue());
    }

    private static String publication(WorkspaceId workspace) {
        return "/api/auth/workspaces/" + workspace + "/publication";
    }
}
