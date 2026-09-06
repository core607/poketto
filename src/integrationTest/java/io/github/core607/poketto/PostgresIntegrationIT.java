package io.github.core607.poketto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private PublicContentSnapshots snapshots;

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("defaultWorkspaceInitializer")
    private ApplicationRunner defaultWorkspaceInitializer;

    @Test
    void providesPostgres17WithUtf8TextSupport() {
        Integer majorVersion =
                jdbc.queryForObject("select current_setting('server_version_num')::integer / 10000", Integer.class);
        assertThat(majorVersion).isEqualTo(17);

        assertThat(jdbc.queryForObject("show server_encoding", String.class)).isEqualTo("UTF8");
        assertThat(jdbc.queryForObject("select ?::text", String.class, "知识库成员")).isEqualTo("知识库成员");
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
    void servesRepositoryNativeDocumentsOnlyAfterPublishingPolicyIsEnabled() throws Exception {
        Workspace workspace = workspaces.defaultWorkspace();
        Path author = Files.createTempDirectory(dataDirectory, "author-");
        try (Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(author.toFile())
                .call()) {
            Files.createDirectories(author.resolve("private"));
            Files.writeString(author.resolve("private/secret.md"), "# Secret\nprivate body");
            Files.writeString(author.resolve("hello.md"), "# Hello\n\nPublic body");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Test Author", "test@invalid")
                    .setMessage("Initial notes")
                    .call();
            String remote = dataDirectory.resolve("remote.git").toUri().toString();
            git.push()
                    .setRemote(remote)
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
            snapshots.refresh(workspace.id());
            mvc.perform(get("/api/public/documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            Files.createDirectories(author.resolve(".poketto"));
            Files.writeString(author.resolve(".poketto/publishing.yaml"), "enabled: true\nmode: public-by-default\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Test Author", "test@invalid")
                    .setMessage("Enable publication")
                    .call();
            git.push()
                    .setRemote(remote)
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
            snapshots.refresh(workspace.id());
        }
        mvc.perform(get("/api/public/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].route").value("/hello"));
        mvc.perform(get("/api/public/document").param("route", "/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hello"))
                .andExpect(jsonPath("$.body").value("# Hello\n\nPublic body"));
        mvc.perform(get("/api/public/document").param("route", "/private/secret"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/documents").param("query", "private body"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
