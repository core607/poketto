package io.github.core607.poketto.spaces.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.RegistrationService;
import io.github.core607.poketto.content.RepositoryConnections;
import io.github.core607.poketto.spaces.SpaceCreationService;
import io.github.core607.poketto.workspace.WorkspaceRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class SpacesConfiguration {
    @Bean
    SpaceCreationService spaceCreationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            RegistrationService accounts,
            AuthService auth,
            WorkspaceRegistry workspaces,
            RepositoryConnections repositories) {
        return new SpaceCreationService(
                jdbc, transactions, accounts, auth, workspaces, repositories, Clock.systemUTC());
    }
}
