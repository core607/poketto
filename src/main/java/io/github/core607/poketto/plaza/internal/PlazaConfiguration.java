package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.community.MachineCommunity;
import io.github.core607.poketto.games.GameSaves;
import io.github.core607.poketto.plaza.PlazaService;
import io.github.core607.poketto.qa.QaService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"poketto.workspace.catalog.enabled", "poketto.plaza.enabled"},
        havingValue = "true",
        matchIfMissing = true)
class PlazaConfiguration {
    @Bean
    PlazaService plazaService(
            MachineAccounts accounts,
            PublicPlazaReads reads,
            JdbcTemplate jdbc,
            MachineCommunity interactions,
            ObjectProvider<GameSaves> games,
            ObjectProvider<QaService> qa,
            @Value("${poketto.plaza.interactions-enabled:true}") boolean interactionsEnabled) {
        Clock clock = Clock.systemUTC();
        return new DefaultPlazaService(
                accounts,
                reads,
                new PlazaPocket(jdbc, clock),
                new PlazaStreet(clock),
                new PlazaWallet(jdbc, clock),
                interactions,
                interactionsEnabled,
                games.getIfAvailable(),
                qa.getIfAvailable());
    }
}
