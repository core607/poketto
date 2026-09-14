package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.internal.PublicationRepositories;
import io.github.core607.poketto.spaces.SpacePublicationService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
@Import(PublicationRepositories.class)
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
        seed(remote, "Visible", 0);
        Path second = directory.resolve("second.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(second.toFile())
                .call()) {}
        seed(second, "Second unique sentinel", 0xff00ff);
        properties.add("poketto.data-dir", directory::toString);
        properties.add("poketto.test.repository-path", remote::toString);
    }

    private static void seed(Path remote, String title, int color) throws Exception {
        Path root = directory.resolve(remote.getFileName() + "-seed");
        try (Git git =
                Git.init().setInitialBranch("main").setDirectory(root.toFile()).call()) {
            Files.createDirectories(root.resolve(".poketto"));
            Files.createDirectories(root.resolve("public"));
            Files.createDirectories(root.resolve("private"));
            Files.writeString(root.resolve(".poketto/publishing.yaml"), "enabled: true\nmode: public-root\n");
            Files.writeString(
                    root.resolve("public/note.md"),
                    "# " + title + "\n\n![Picture](picture.png)\n\n[Download](source.pdf)\n");
            Files.writeString(root.resolve("public/source.pdf"), "%PDF-1.7\n" + title);
            Files.writeString(root.resolve("private/secret.md"), "# Private sentinel\n");
            var picture = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            picture.setRGB(0, 0, color);
            ImageIO.write(picture, "png", root.resolve("public/picture.png").toFile());
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
    RepositoryContentReader content;

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
                .andExpect(jsonPath("$.workspaceId").value(workspace.toString()))
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
        verifyAuthorNames(owner, workspace, ownerSession);
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
        mvc.perform(csrf(session, put(publication(workspace) + "/author"))
                        .contentType("application/json")
                        .content("{\"name\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(csrf(session, put(publication(workspace)))
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        String token = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.MANAGE_KEYS))
                .token();
        assertThatThrownBy(() -> service.setEnabled(auth.authenticateApiKey(token), workspace, true))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.setAuthorName(auth.authenticateApiKey(token), workspace, "Denied"))
                .isInstanceOf(AuthException.class);
    }

    private void verifyAuthorNames(AuthPrincipal owner, WorkspaceId workspace, MockHttpSession session)
            throws Exception {
        seedAuthors(workspace);
        String endpoint = "/api/public/spaces/home/document";
        String authorEndpoint = publication(workspace) + "/author";
        mvc.perform(put(authorEndpoint)
                        .session(session)
                        .contentType("application/json")
                        .content("{\"name\":\"Denied\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(csrf(session, put(authorEndpoint))
                        .contentType("application/json")
                        .content("{\"name\":\"  Space signature  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicAuthorName").value("Space signature"));
        mvc.perform(get(endpoint).param("route", "/signed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName").value("Article signature"));
        mvc.perform(get(endpoint).param("route", "/unsigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName").value("Space signature"));
        String batch = json.readTree(mvc.perform(get("/api/public/discovery"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("batch")
                .stringValue();
        service.setAuthorName(owner, workspace, "Changed signature");
        mvc.perform(get("/api/public/discovery").param("batch", batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.route == '/unsigned')].authorName")
                        .value(contains("Changed signature")))
                .andExpect(
                        jsonPath("$.items[?(@.route == '/signed')].authorName").value(contains("Article signature")));
        mvc.perform(get("/api/public/spaces/home/documents").param("query", "Unsigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].authorName").value("Changed signature"));
        assertThat(publications.settings(PublicationRepositories.SECOND).publicAuthorName())
                .isEmpty();
        mvc.perform(csrf(session, put(authorEndpoint))
                        .contentType("application/json")
                        .content(
                                json.writeValueAsString(new SpacePublicationController.UpdateAuthor("🐾".repeat(120)))))
                .andExpect(status().isOk());
        mvc.perform(csrf(session, put(authorEndpoint))
                        .contentType("application/json")
                        .content("{\"name\":\"" + "🐾".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
        service.setAuthorName(owner, workspace, "");
        mvc.perform(get(endpoint).param("route", "/unsigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName")
                        .value(catalog.defaultWorkspace().displayName()));
        service.setEnabled(owner, workspace, false);
        mvc.perform(get("/api/public/discovery").param("batch", batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        service.setEnabled(owner, workspace, true);
    }

    private void seedAuthors(WorkspaceId workspace) throws Exception {
        Path root = directory.resolve("remote.git-seed");
        String signed = "---\npublic_author: '  Article signature  '\nauthor: private-author-sentinel\n---\n# Signed\n";
        try (Git git = Git.open(root.toFile())) {
            Files.writeString(root.resolve("public/signed.md"), signed);
            Files.writeString(
                    root.resolve("public/unsigned.md"),
                    "---\npublic_author: '  '\nauthor: private-author-sentinel\n---\n# Unsigned\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Private Git identity", "private@example.invalid")
                    .setMessage("Add signatures")
                    .call();
            git.push().setRemote("origin").setPushAll().call();
        }
        snapshots.refresh(workspace);
        String response = mvc.perform(get("/api/public/spaces/home/document").param("route", "/signed"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response)
                .doesNotContain("private-author-sentinel", "Private Git identity", "private@example.invalid");
        assertThat(Files.readString(root.resolve("public/signed.md"))).isEqualTo(signed);
    }

    private void verifyCatalog(AuthPrincipal owner, WorkspaceId first) throws Exception {
        WorkspaceId second = PublicationRepositories.SECOND;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            registry.create(second, "Second site", "second-site");
            auth.establishWorkspaceOwner(owner, second);
        });
        assertThat(publications.settings(second).enabled()).isFalse();
        assertThat(publications.findPublished("second-site")).isEmpty();
        assertThat(publications.findPublished("../second-site")).isEmpty();
        mvc.perform(get("/api/public/spaces/second-site")).andExpect(status().isNotFound());
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
        verifyScopedContent(owner, first, second);
    }

    private void verifyScopedContent(AuthPrincipal owner, WorkspaceId first, WorkspaceId second) throws Exception {
        snapshots.refresh(second);
        mvc.perform(get("/api/public/spaces/home")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/spaces/second-site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Second site"))
                .andExpect(jsonPath("$.workspaceId").doesNotExist());
        var result = mvc.perform(get("/api/public/spaces/second-site/document").param("route", "/note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Second unique sentinel"))
                .andReturn();
        var document = json.readTree(result.getResponse().getContentAsString());
        String image = document.get("images").get("picture.png").stringValue();
        byte[] delivered = mvc.perform(get(image))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (var stream = new ByteArrayInputStream(delivered)) {
            assertThat(ImageIO.read(stream).getRGB(0, 0) & 0xffffff).isEqualTo(0xff00ff);
        }
        mvc.perform(get("/api/public/spaces/second-site/documents").param("query", "Visible"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/public/spaces/second-site/document").param("route", "/secret"))
                .andExpect(status().isNotFound());
        service.setEnabled(owner, first, true);
        String batch = verifyDiscovery();
        service.setEnabled(owner, second, false);
        mvc.perform(get("/api/public/spaces/second-site/documents")).andExpect(status().isNotFound());
        mvc.perform(get(image)).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/public/discovery").param("batch", batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].space").value("home"));
        verifyCollectionReading(owner, second);
    }

    private void verifyCollectionReading(AuthPrincipal owner, WorkspaceId workspace) throws Exception {
        Path root = directory.resolve("second.git-seed");
        try (Git git = Git.open(root.toFile())) {
            Files.createDirectories(root.resolve("public/broken"));
            Files.writeString(root.resolve("public/broken/index.md"), boundedMarkdown());
            Files.createDirectories(root.resolve("public/guide"));
            Files.writeString(root.resolve("public/end.md"), "# Final article");
            Files.writeString(
                    root.resolve("public/guide/index.md"),
                    "# Ordered guide\n\n[First](../note.md#part)\n[Again](../note.md)\n"
                            + "[Hidden](../../private/secret.md)\n[Missing](missing.md)\n"
                            + "[External](https://example.invalid)\n[Last](../end.md)\n");
            Files.writeString(root.resolve("public/index.md"), "# Another guide\n\n[Same article](note.md)\n");
            seedFolderLandings(root);
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Fixture", "fixture@example.invalid")
                    .setMessage("Add reading guides")
                    .call();
            git.push().setRemote("origin").setPushAll().call();
        }
        snapshots.refresh(workspace);
        service.setEnabled(owner, workspace, true);
        String endpoint = "/api/public/spaces/second-site/document";
        verifyFolderLandings(endpoint, workspace);
        mvc.perform(get(endpoint).param("route", "/broken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body", containsString("BOUNDARY_SENTINEL")))
                .andExpect(jsonPath("$.body", containsString("![Approved image](../picture.png)")))
                .andExpect(jsonPath("$.navigation.available").value(false))
                .andExpect(jsonPath("$.links").isEmpty())
                .andExpect(jsonPath("$.downloads").isEmpty())
                .andExpect(jsonPath("$.images").isEmpty())
                .andExpect(jsonPath("$.gallery").isEmpty())
                .andExpect(jsonPath("$.galleryStatus").value("UNAVAILABLE"));
        mvc.perform(get(endpoint).param("route", "/guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.available").value(true))
                .andExpect(jsonPath("$.navigation.entries.length()").value(2))
                .andExpect(jsonPath("$.navigation.entries[0].route").value("/note"))
                .andExpect(jsonPath("$.navigation.entries[1].route").value("/end"));
        mvc.perform(get(endpoint).param("route", "/note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.memberships.length()").value(2))
                .andExpect(jsonPath("$.navigation.memberships[?(@.collection.route == '/guide')].next.route")
                        .value(contains("/end")));
        mvc.perform(get(endpoint).param("route", "/end"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.navigation.memberships[0].previous.route").value("/note"))
                .andExpect(jsonPath("$.navigation.memberships[0].position").value(2))
                .andExpect(jsonPath("$.navigation.memberships[0].next").isEmpty());
        mvc.perform(get("/api/public/spaces/home/document").param("route", "/note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.memberships").isEmpty());
        service.setEnabled(owner, workspace, false);
        mvc.perform(get(endpoint).param("route", "/guide")).andExpect(status().isNotFound());
    }

    private static void seedFolderLandings(Path root) throws Exception {
        Files.createDirectories(root.resolve("public/album"));
        Files.writeString(root.resolve("public/guide/README.md"), "# Shadowed landing source");
        Files.writeString(root.resolve("public/album/README.md"), "# Album from README\n\nOriginal caption.");
        Files.writeString(root.resolve("public/album/index.md"), "# Excluded index sentinel");
        Files.writeString(
                root.resolve(".poketto/publishing.yaml"),
                "enabled: true\nmode: public-root\nexclude: [public/album/index.md]\n");
        Files.writeString(
                root.resolve("public/index.md"), "# Another guide\n\n[Same article](note.md)\n[Album](album/)\n");
        Files.copy(root.resolve("public/picture.png"), root.resolve("public/album/first.png"));
        Files.copy(root.resolve("public/picture.png"), root.resolve("public/album/second.png"));
    }

    private void verifyFolderLandings(String endpoint, WorkspaceId workspace) throws Exception {
        mvc.perform(get(endpoint).param("route", "/album"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Album from README"))
                .andExpect(jsonPath("$.folderPage").value(true))
                .andExpect(jsonPath("$.gallery.length()").value(2));
        mvc.perform(get(endpoint).param("route", "/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links['album/']").value("/album"));
        mvc.perform(get("/api/public/spaces/second-site/documents").param("query", "Shadowed landing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        assertThat(content.getFile(workspace, Optional.empty(), "public/guide/README.md")
                        .source())
                .contains("# Shadowed landing source");
    }

    private static String boundedMarkdown() {
        StringBuilder body = new StringBuilder("# BOUNDARY_SENTINEL\n\n")
                .append("[Approved article](../note.md)\n\n")
                .append("[Approved download](../source.pdf)\n\n")
                .append("![Approved image](../picture.png)\n\n");
        for (int index = 0; index < 256; index++) {
            body.append("[Overflow ")
                    .append(index)
                    .append("](<missing-")
                    .append(index)
                    .append(".md>)\n");
        }
        return body.toString();
    }

    private String verifyDiscovery() throws Exception {
        var first = mvc.perform(get("/api/public/discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var parsed = json.readTree(first);
        String batch = parsed.get("batch").stringValue();
        var replay = mvc.perform(get("/api/public/discovery").param("batch", batch))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(json.readTree(replay)).isEqualTo(parsed);
        assertThat(first).contains("Second unique sentinel", "Visible").doesNotContain("Private sentinel");
        mvc.perform(get("/api/public/discovery").param("batch", "missing")).andExpect(status().isGone());
        return batch;
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
                .andExpect(jsonPath("$.workspaceId").value(workspace.toString()))
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
