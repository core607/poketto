package io.github.core607.poketto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
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

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse(System.getProperty("poketto.postgres.image"))
            .asCompatibleSubstituteFor("postgres");

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
    @Qualifier("defaultWorkspaceInitializer")
    private ApplicationRunner defaultWorkspaceInitializer;

    @Test
    void providesPostgres17WithWorkingZhparser() {
        Integer majorVersion = jdbc.queryForObject(
                "select current_setting('server_version_num')::integer / 10000",
                Integer.class);
        assertThat(majorVersion).isEqualTo(17);

        jdbc.execute("create extension zhparser");
        jdbc.execute("create text search configuration poketto_zh (parser = zhparser)");
        jdbc.execute("alter text search configuration poketto_zh "
                + "add mapping for n,v,a,i,e,l with simple");

        Integer tokenCount = jdbc.queryForObject(
                "select count(*) from ts_parse('zhparser', '知识库支持中文搜索')",
                Integer.class);
        assertThat(tokenCount).isPositive();
    }

    @Test
    void initializesAndExposesOneDefaultWorkspace() {
        Workspace defaultWorkspace = workspaces.defaultWorkspace();

        assertThat(workspaces.findById(defaultWorkspace.id())).contains(defaultWorkspace);
        assertThatNullPointerException()
                .isThrownBy(() -> workspaces.findById(null))
                .withMessage("workspace id must not be null");
        assertThat(jdbc.queryForObject(
                        "select count(*) from workspaces",
                        Integer.class))
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
        assertThat(jdbc.queryForObject(
                        "select count(*) from workspaces",
                        Integer.class))
                .isOne();
    }
}
