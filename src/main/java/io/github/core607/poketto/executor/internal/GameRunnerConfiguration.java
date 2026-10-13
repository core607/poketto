package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.games.enabled", havingValue = "true")
class GameRunnerConfiguration {
    @Bean
    IsolatedGameRunner gameRunner(
            ObjectMapper json,
            @Value("${poketto.games.socket}") Path socket,
            @Value("${poketto.games.signing-key}") Path key,
            @Value("${poketto.games.max-jobs:2}") int jobs) {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            throw new IllegalStateException("The game worker requires Linux containment");
        }
        if (jobs < 1 || jobs > 16) {
            throw new IllegalArgumentException("Game concurrency must be between 1 and 16");
        }
        return new IsolatedGameRunner(ExecutorConfiguration.workerClient(json, socket, key), json, jobs);
    }
}
