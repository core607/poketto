package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.games.GameSaves;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.games.enabled", havingValue = "true")
class GamesConfiguration {
    @Bean
    GameSaves gameSaves(
            CommunityAccounts people,
            MachineAccounts machines,
            PublicationGuard guard,
            GameLibrary library,
            GameRunner runner,
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactions) {
        return new DefaultGameSaves(
                new GameScope(people, machines, guard, library, transactions),
                new GameSaveStore(jdbc, json, Clock.systemUTC()),
                library,
                runner,
                publications,
                snapshots,
                json);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    DefaultGameLibrary gameLibrary(
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            RepositoryBlobReader blobs,
            GameRunner runner,
            ObjectMapper json) {
        return new DefaultGameLibrary(publications, snapshots, new GamePackages(blobs), runner, json);
    }
}
