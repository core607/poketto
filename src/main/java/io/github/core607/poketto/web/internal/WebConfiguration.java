package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class WebConfiguration {
    @Bean
    FilterRegistrationBean<ImageMemoryFilter> imageMemoryFilter(ImageMemoryAdmission admission) {
        var registration = new FilterRegistrationBean<>(new ImageMemoryFilter(admission));
        registration.setUrlPatterns(java.util.List.of("/api/*"));
        registration.setOrder(-99);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    PublicDocuments publicDocuments(PublicContentSnapshots store, WorkspaceCatalog workspaces, AssetService assets) {
        return new PublicDocuments(store, workspaces, assets);
    }
}
