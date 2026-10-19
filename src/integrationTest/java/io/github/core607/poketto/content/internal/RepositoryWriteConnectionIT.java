package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RepositoryWriteConnectionIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @Test
    void aQueuedWriteLeavesTheLastConnectionAvailableToTheRepositoryReader() throws Exception {
        assertConnectionAvailable(true);
    }

    @Test
    void aQueuedMovePlanLeavesTheLastConnectionAvailableToTheRepositoryReader() throws Exception {
        assertConnectionAvailable(false);
    }

    private void assertConnectionAvailable(boolean writing) throws Exception {
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
            when(auth.withAuthorization(any(), any(), any(), any()))
                    .thenAnswer(call -> transactions.execute(status -> ((Supplier<?>) call.getArgument(3)).get()));
            var waiting = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var authority = blockedAuthority(waiting, release);
            var service = new JGitRepositoryPatchService(
                    authority,
                    auth,
                    Clock.systemUTC(),
                    (id, snapshot) -> {},
                    (id, snapshot) -> {},
                    mock(RepositoryMediaValidator.class));
            var actor = mock(AuthPrincipal.class);
            WorkspaceId workspace = WorkspaceId.random();
            var pending = workers.submit(() -> {
                if (writing) {
                    return service.apply(
                            actor,
                            workspace,
                            new RepositoryPatch(
                                    Optional.empty(),
                                    List.of(new RepositoryTextChange(
                                            "private/new.md", true, Optional.empty(), Optional.of("new")))));
                }
                return service.plan(
                        actor,
                        workspace,
                        new RepositoryMoveRequest("a".repeat(40), "private/old.md", "private/new.md"));
            });
            try {
                assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
                // The reader owning the repository lock must still be able to resolve its binding.
                assertThat(new JdbcTemplate(pool).queryForObject("select 1", Integer.class))
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ContentRepositoryException.class)
                    .hasRootCauseMessage("repository wait released");
        }
    }

    private RepositoryAuthority blockedAuthority(CountDownLatch waiting, CountDownLatch release) {
        var authority = mock(RepositoryAuthority.class);
        when(authority.withPreparedCredentials(any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(authority.writeObjects(any(), any(), any())).thenAnswer(call -> {
            waitForRelease(waiting, release);
            throw new ContentRepositoryException("repository wait released");
        });
        when(authority.readObjects(any(), any())).thenAnswer(call -> {
            waitForRelease(waiting, release);
            throw new ContentRepositoryException("repository wait released");
        });
        return authority;
    }

    private void waitForRelease(CountDownLatch waiting, CountDownLatch release) throws InterruptedException {
        waiting.countDown();
        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
