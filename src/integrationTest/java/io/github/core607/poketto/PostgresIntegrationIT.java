package io.github.core607.poketto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class PostgresIntegrationIT {

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
