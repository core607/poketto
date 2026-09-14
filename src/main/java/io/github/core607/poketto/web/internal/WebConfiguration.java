package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class WebConfiguration {
    @Bean
    PublicSitemaps publicSitemaps(PublicContentSnapshots snapshots, WorkspacePublications publications) {
        return new PublicSitemaps(publications, new WebsiteContentSnapshots(snapshots, publications));
    }

    @Bean
    PublicDiscovery publicDiscovery(
            PublicContentSnapshots snapshots, WorkspacePublications publications, AssetService assets) {
        return new PublicDiscovery(
                publications, new WebsiteContentSnapshots(snapshots, publications), assets, Clock.systemUTC());
    }

    // Ordered ahead of every other filter so a request rejected by admission, origin, or
    // authentication still leaves a record. Asynchronous support is required: the MCP transport
    // completes on a dispatch thread.
    @Bean
    FilterRegistrationBean<RequestDiagnosticsFilter> requestDiagnosticsFilter() {
        var registration = new FilterRegistrationBean<>(new RequestDiagnosticsFilter());
        registration.setUrlPatterns(List.of("/*"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ImageMemoryFilter> imageMemoryFilter(ImageMemoryAdmission admission) {
        var registration = new FilterRegistrationBean<>(new ImageMemoryFilter(admission));
        registration.setUrlPatterns(List.of("/api/*"));
        registration.setOrder(-99);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    PublicDocuments publicDocuments(
            PublicContentSnapshots store,
            WorkspaceCatalog workspaces,
            AssetService assets,
            WorkspacePublications publications) {
        return new PublicDocuments(new WebsiteContentSnapshots(store, publications), workspaces, assets, publications);
    }
}
