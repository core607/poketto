package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(RemoteRepositoryIntegrationConfiguration.class)
class RepositoryPatchIntegrationIT {
    @TempDir
    static Path data;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path remote = data.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            registry.add(
                    "poketto.test.repository-path",
                    () -> remote.toAbsolutePath().toString());
        } catch (Exception exception) {
            throw new IllegalStateException("synthetic Git remote could not be prepared", exception);
        }
        registry.add("poketto.data-dir", () -> data.toAbsolutePath().toString());
    }

    @Autowired
    AuthService auth;

    @Autowired
    WorkspaceCatalog workspaces;

    @Autowired
    RepositoryPatchService patches;

    @Autowired
    RepositoryMoveService moves;

    @Autowired
    RepositoryContentReader files;

    @Autowired
    PublicContentSnapshots snapshots;

    @Autowired
    MockMvc mvc;

    @Test
    void liveKeyPermissionsAndGitAcknowledgementControlThePublicSnapshot() throws Exception {
        var workspace = workspaces.defaultWorkspace().id();
        var owner = auth.initializeOwner("owner", UUID.randomUUID().toString());
        var issued = auth.createApiKey(owner, workspace, owner.accountId(), null);
        var agent = auth.authenticateApiKey(issued.token());
        var draft = patches.apply(
                agent,
                workspace,
                new RepositoryPatch(Optional.empty(), List.of(create("private/draft.md", "# Draft"))));
        assertThat(draft.committed()).isTrue();
        assertThat(draft.snapshotUpdated()).isTrue();
        assertThat(snapshots.current(workspace).articles()).isEmpty();
        String policy = "enabled: true\nmode: public-root\n";
        var publish = new RepositoryPatch(
                Optional.of(draft.commit()),
                List.of(create(RepositoryPublishingPolicy.PATH, policy), create("public/article.md", "# Public")));
        assertThatThrownBy(() -> patches.apply(agent, workspace, publish)).isInstanceOf(AuthException.class);
        var published = patches.apply(owner, workspace, publish);
        assertThat(published.snapshotUpdated()).isTrue();
        assertThat(snapshots.current(workspace).commit()).contains(published.commit());
        assertThat(snapshots.current(workspace).articles()).hasSize(1);

        var before = files.getFile(workspace, Optional.of(published.commit()), "private/draft.md");
        var privateEdit = new RepositoryPatch(
                Optional.of(published.commit()),
                List.of(new RepositoryTextChange(
                        before.path(),
                        false,
                        before.revision(),
                        Optional.of("---\nroute: /article\n---\n# Private alias"))));
        var edited = patches.apply(agent, workspace, privateEdit);
        assertThat(snapshots.current(workspace).commit()).contains(edited.commit());
        assertThat(snapshots.current(workspace).articles()).hasSize(1);
        assertThat(snapshots.current(workspace).articles().getFirst().title()).isEqualTo("Public");

        var relocated = moves.move(
                agent,
                workspace,
                new RepositoryMoveRequest(edited.commit(), "private/draft.md", "private/lists/draft.md"));
        assertThat(files.getFile(workspace, Optional.empty(), "private/draft.md")
                        .expectedAbsence())
                .isTrue();
        assertThat(files.getFile(workspace, Optional.empty(), "private/lists/draft.md")
                        .source())
                .contains("---\nroute: /article\n---\n# Private alias");
        var publicMove = new RepositoryMoveRequest(relocated.commit(), "public/article.md", "public/moved-article.md");
        assertThatThrownBy(() -> moves.move(agent, workspace, publicMove)).isInstanceOf(AuthException.class);
        var movedPublic = moves.move(owner, workspace, publicMove);
        assertThat(snapshots.current(workspace).commit()).contains(movedPublic.commit());
        assertThat(snapshots.current(workspace).articles())
                .singleElement()
                .extracting(a -> a.route())
                .isEqualTo("/moved-article");

        auth.revokeApiKey(owner, workspace, issued.id());
        assertThatThrownBy(() -> moves.move(
                        agent,
                        workspace,
                        new RepositoryMoveRequest(
                                movedPublic.commit(), "private/lists/draft.md", "private/forbidden.md")))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> patches.apply(
                        agent,
                        workspace,
                        new RepositoryPatch(
                                Optional.of(movedPublic.commit()),
                                List.of(create("private/forbidden.md", "# Denied")))))
                .isInstanceOf(AuthException.class);
        assertThat(files.getFile(workspace, Optional.empty(), "private/forbidden.md")
                        .expectedAbsence())
                .isTrue();

        String frontmatter = "---\nroute: /stale-source\ncustom: keep\n---\n";
        String source = frontmatter + "# Source\n\n[Article](../moved-article.md)\n[Asset](asset.bin)\n";
        var routeFixture = patches.apply(
                owner,
                workspace,
                new RepositoryPatch(
                        Optional.of(movedPublic.commit()),
                        List.of(
                                create("public/source/README.md", source),
                                create("public/source/asset.bin", "git-media"),
                                create("public/sibling/README.md", "---\nroute: /stale-sibling\n---\n# Sibling"),
                                create("private/reader.md", "[Source](../public/source/README.md)\n"))));
        snapshots.refresh(workspace);
        mvc.perform(get("/api/public/document").param("route", "/source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderPage").value(true));
        mvc.perform(get("/api/public/document").param("route", "/stale-source")).andExpect(status().isNotFound());
        assertThat(snapshots.current(workspace).articles())
                .extracting(article -> article.route())
                .contains("/source", "/sibling", "/moved-article")
                .doesNotContain("/stale-source", "/stale-sibling");

        var nested = moves.move(
                owner,
                workspace,
                new RepositoryMoveRequest(routeFixture.commit(), "public/source", "public/archive/source"));
        assertThat(files.getFile(workspace, Optional.empty(), "public/archive/source/README.md")
                        .source())
                .hasValueSatisfying(
                        value -> assertThat(value).startsWith(frontmatter).contains("../../moved-article.md"));
        assertThat(files.getFile(workspace, Optional.empty(), "public/archive/source/asset.bin")
                        .source())
                .contains("git-media");
        assertThat(files.getFile(workspace, Optional.empty(), "private/reader.md")
                        .source())
                .hasValueSatisfying(value -> assertThat(value).contains("../public/archive/source/README.md"));
        snapshots.refresh(workspace);
        mvc.perform(get("/api/public/document").param("route", "/archive/source"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/document").param("route", "/stale-source")).andExpect(status().isNotFound());

        var privateMove = moves.move(
                owner,
                workspace,
                new RepositoryMoveRequest(nested.commit(), "public/archive/source", "private/relocated"));
        assertThat(files.getFile(workspace, Optional.empty(), "private/relocated/README.md")
                        .source())
                .hasValueSatisfying(value -> assertThat(value).startsWith(frontmatter));
        assertThat(files.getFile(workspace, Optional.empty(), "private/reader.md")
                        .source())
                .hasValueSatisfying(value -> assertThat(value).contains("relocated/README.md"));
        snapshots.refresh(workspace);
        mvc.perform(get("/api/public/document").param("route", "/archive/source"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/document").param("route", "/moved-article"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Public"));
        assertThat(snapshots.current(workspace).articles())
                .extracting(article -> article.route())
                .doesNotContain("/archive/source", "/stale-source", "/stale-sibling")
                .contains("/sibling", "/moved-article");
        assertThatThrownBy(() -> new RepositoryMoveRequest(
                        privateMove.commit(), "public/moved-article.md", "public/../unsafe.md"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyCaptureKeys(owner, workspace);
    }

    // A capture key adds new inbox notes over its own entrance and can do nothing else to the repository.
    private void verifyCaptureKeys(AuthPrincipal owner, WorkspaceId workspace) throws Exception {
        String token = auth.createApiKey(owner, workspace, owner.accountId(), Set.of(Capability.CAPTURE))
                .token();
        String created = mvc.perform(
                        post("/api/capture")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"雨后\",\"url\":\"https://example.com/rain\",\"text\":\"一段话\",\"note\":\"备注\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String path = JsonMapper.builder().build().readTree(created).get("path").stringValue();
        assertThat(path).startsWith("private/inbox/").endsWith("-雨后.md");
        assertThat(files.getFile(workspace, Optional.empty(), path).source())
                .hasValueSatisfying(value -> assertThat(value)
                        .contains("source: \"https://example.com/rain\"")
                        .contains("> 一段话")
                        .endsWith("备注\n"));

        mvc.perform(post("/api/capture").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/capture")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());

        AuthPrincipal capture = auth.authenticateApiKey(token);
        var current = files.getFile(workspace, Optional.empty(), path);
        assertThatThrownBy(() -> patches.apply(
                        capture,
                        workspace,
                        new RepositoryPatch(current.commit(), List.of(create("private/elsewhere.md", "# No")))))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> patches.apply(
                        capture,
                        workspace,
                        new RepositoryPatch(
                                current.commit(),
                                List.of(new RepositoryTextChange(
                                        path, false, current.revision(), Optional.of("# Overwritten"))))))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> patches.apply(
                        capture,
                        workspace,
                        new RepositoryPatch(current.commit(), List.of(create("private/inbox/nested/x.md", "# No")))))
                .isInstanceOf(AuthException.class);

        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        var sent = mvc.perform(multipart("/api/capture")
                        .file(new MockMultipartFile("image", "photo.png", "image/png", image.toByteArray()))
                        .param("note", "照片")
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
        // Managed originals are stored on Linux only; elsewhere the store refuses and nothing is written.
        if (OS.current() != OS.LINUX) {
            assertThat(sent.getStatus()).isEqualTo(503);
            return;
        }
        assertThat(sent.getStatus()).isEqualTo(201);
        String withImage = JsonMapper.builder()
                .build()
                .readTree(sent.getContentAsString())
                .get("path")
                .stringValue();
        assertThat(files.getFile(workspace, Optional.empty(), withImage).source())
                .hasValueSatisfying(
                        value -> assertThat(value).containsPattern("!\\[图片]\\(managed:[0-9a-f-]{36}:[0-9a-f]{64}\\)"));
    }

    private static RepositoryTextChange create(String path, String content) {
        return new RepositoryTextChange(path, true, Optional.empty(), Optional.of(content));
    }
}
