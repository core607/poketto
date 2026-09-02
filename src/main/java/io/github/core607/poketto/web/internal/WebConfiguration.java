package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class WebConfiguration {

    @Bean
    PublicDocuments publicDocuments(ContentRepositoryStore store, WorkspaceCatalog workspaces) {
        return new PublicDocuments(store, workspaces);
    }
}
