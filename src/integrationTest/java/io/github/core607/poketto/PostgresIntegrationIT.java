package io.github.core607.poketto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.DocumentDraft;
import io.github.core607.poketto.content.DocumentWriteResult;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(io.github.core607.poketto.content.internal.RemoteRepositoryIntegrationConfiguration.class)
class PostgresIntegrationIT {

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void contentProperties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", () -> dataDirectory.toAbsolutePath().toString());
        Path remote = dataDirectory.resolve("remote.git").toAbsolutePath();
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            // A local bare repository gives the application a real remote protocol in CI.
        } catch (Exception exception) {
            throw new IllegalStateException("integration-test remote cannot be created", exception);
        }
        registry.add("poketto.test.repository-path", remote::toString);
    }

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceCatalog workspaces;

    @Autowired
    private WorkspacePaths workspacePaths;

    @Autowired
    private DocumentWriteService writes;

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("defaultWorkspaceInitializer")
    private ApplicationRunner defaultWorkspaceInitializer;

    @Test
    void providesPostgres17WithWorkingZhparser() {
        Integer majorVersion =
                jdbc.queryForObject("select current_setting('server_version_num')::integer / 10000", Integer.class);
        assertThat(majorVersion).isEqualTo(17);

        jdbc.execute("create extension zhparser");
        jdbc.execute("create text search configuration poketto_zh (parser = zhparser)");
        jdbc.execute("alter text search configuration poketto_zh " + "add mapping for n,v,a,i,e,l with simple");

        Integer tokenCount =
                jdbc.queryForObject("select count(*) from ts_parse('zhparser', '知识库支持中文搜索')", Integer.class);
        assertThat(tokenCount).isPositive();
    }

    @Test
    void initializesAndExposesOneDefaultWorkspace() {
        Workspace defaultWorkspace = workspaces.defaultWorkspace();

        assertThat(workspaces.findById(defaultWorkspace.id())).contains(defaultWorkspace);
        assertThatNullPointerException()
                .isThrownBy(() -> workspaces.findById(null))
                .withMessage("workspace id must not be null");
        assertThat(jdbc.queryForObject("select count(*) from workspaces", Integer.class))
                .isOne();
        assertThat(Files.isDirectory(
                        workspacePaths.contentDirectory(defaultWorkspace.id()).resolve(".git")))
                .isTrue();
    }

    @Test
    void reusesTheStoredDefaultWorkspaceWhenInitializationRunsAgain() throws Exception {
        Workspace before = workspaces.defaultWorkspace();

        // Rerunning the initializer bean stands in for a later application start.
        defaultWorkspaceInitializer.run(new DefaultApplicationArguments());

        assertThat(workspaces.defaultWorkspace()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from workspaces", Integer.class))
                .isOne();
    }

    @Test
    void reportsHealthWithTheDatabaseAndRepositoryReady() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void servesOnlyPublishedDocumentsThroughThePublicEntrance() throws Exception {
        Workspace workspace = workspaces.defaultWorkspace();
        DocumentWriteResult secret = writes.create(
                workspace.id(),
                WritePrincipal.SYSTEM,
                new DocumentDraft("documents/it/secret.md", "Secret", List.of(), "private body"));
        DocumentWriteResult draft = writes.create(
                workspace.id(),
                WritePrincipal.SYSTEM,
                new DocumentDraft("documents/it/hello.md", "Hello", List.of("intro"), "# Hello\n\nPublic body"));

        mvc.perform(get("/api/public/documents/" + draft.documentId())).andExpect(status().isNotFound());

        writes.publish(
                workspace.id(),
                WritePrincipal.SYSTEM,
                draft.documentId(),
                draft.revision().orElseThrow());

        mvc.perform(get("/api/public/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(draft.documentId().toString()));
        mvc.perform(get("/api/public/documents/" + draft.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hello"))
                .andExpect(jsonPath("$.tags[0]").value("intro"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.body").value("# Hello\n\nPublic body"));
        mvc.perform(get("/api/public/documents/" + secret.documentId())).andExpect(status().isNotFound());
    }
}
