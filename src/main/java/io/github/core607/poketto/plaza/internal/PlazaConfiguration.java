package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.plaza.PlazaService;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
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
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            JdbcTemplate jdbc,
            @Value("${poketto.plaza.public-url:${poketto.oauth.issuer:}}") String publicUrl) {
        Clock clock = Clock.systemUTC();
        return new DefaultPlazaService(
                accounts,
                new PublicPlazaReads(publications, new WebsiteContentSnapshots(snapshots, publications), publicUrl),
                new PlazaPocket(jdbc, clock),
                new PlazaStreet(clock));
    }
}
