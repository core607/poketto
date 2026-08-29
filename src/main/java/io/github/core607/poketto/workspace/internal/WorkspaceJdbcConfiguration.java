package io.github.core607.poketto.workspace.internal;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "poketto.workspace.catalog.enabled",
        havingValue = "true",
        matchIfMissing = true)
class WorkspaceJdbcConfiguration {

    @Bean
    JdbcWorkspaceCatalog workspaceCatalog(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcWorkspaceCatalog(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    ApplicationRunner defaultWorkspaceInitializer(JdbcWorkspaceCatalog catalog) {
        return arguments -> catalog.ensureDefaultWorkspace();
    }
}
