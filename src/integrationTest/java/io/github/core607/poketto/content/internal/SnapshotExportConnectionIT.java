package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SnapshotExportConnectionIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path directory;

    @Test
    void waitingForRepositoryDoesNotOccupyTheLastDatabaseConnection() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(500);
        try (var pool = new HikariDataSource(config);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
            var auth = mock(AuthService.class);
            // Keep the real transaction/connection lifetime while isolating the grant from repository latency.
            when(auth.withAuthorization(any(), any(), any(), any()))
                    .thenAnswer(call -> transactions.execute(status -> ((Supplier<?>) call.getArgument(3)).get()));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var authority = mock(RepositoryAuthority.class);
            when(authority.withPreparedCredentials(any(), any()))
                    .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
            when(authority.readObjects(any(), any(), any())).thenAnswer(call -> {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return "a".repeat(40);
            });
            var exports = new JGitRepositorySnapshotExports(
                    authority,
                    auth,
                    directory.resolve("exports"),
                    1024,
                    Duration.ofSeconds(5),
                    mock(PublicContentSnapshots.class));
            var work = workers.submit(
                    () -> exports.resolve(mock(AuthPrincipal.class), WorkspaceId.random(), Optional.empty()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(new JdbcTemplate(pool).queryForObject("select 1", Integer.class))
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThat(work.get(5, TimeUnit.SECONDS)).isEqualTo("a".repeat(40));
        }
    }
}
