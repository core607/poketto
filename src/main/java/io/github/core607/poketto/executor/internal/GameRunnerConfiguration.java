package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.games.GameRunner;
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
            MachineAccounts accounts,
            @Value("${poketto.games.socket}") Path socket,
            @Value("${poketto.data-dir}") Path data,
            @Value("${poketto.games.max-jobs:2}") int jobs) {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            throw new IllegalStateException("The game worker requires Linux containment");
        }
        if (jobs < 1 || jobs > 16) {
            throw new IllegalArgumentException("Game concurrency must be between 1 and 16");
        }
        return new IsolatedGameRunner(
                ExecutorConfiguration.sandcrabClient(socket),
                json,
                jobs,
                data.resolve("game-service.lock"),
                identity -> authorize(accounts, identity));
    }

    private static void authorize(MachineAccounts accounts, GameRunner.Identity identity) {
        if (identity instanceof GameRunner.Player player) {
            accounts.withCreator(player.principal(), player.workspaceId(), current -> {
                if (!current.personal()) {
                    throw new AuthException(AuthException.Code.DENIED);
                }
                return null;
            });
        }
    }

    @Bean
    GameSandboxMaintenance gameSandboxMaintenance(IsolatedGameRunner runner) {
        return new GameSandboxMaintenance(runner);
    }
}
