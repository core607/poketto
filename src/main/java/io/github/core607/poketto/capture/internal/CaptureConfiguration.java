package io.github.core607.poketto.capture.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.capture.CaptureInbox;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryPatchService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class CaptureConfiguration {
    @Bean
    CaptureInbox captureInbox(
            AuthService auth,
            RepositoryContentReader reader,
            RepositoryPatchService patches,
            AssetService assets,
            ObjectMapper json,
            @Value("${poketto.capture.per-minute:30}") int perMinute,
            @Value("${poketto.capture.per-day:500}") int perDay) {
        Clock clock = Clock.systemUTC();
        return new RepositoryCaptureInbox(
                auth, reader, patches, assets, new CaptureLimits(perMinute, perDay, 10_000, clock), json, clock);
    }
}
