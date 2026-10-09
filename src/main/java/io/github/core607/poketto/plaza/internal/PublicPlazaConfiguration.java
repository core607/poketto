package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.games.GameContentSnapshots;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.qa.QaCandy;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaSources;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PublicPlazaConfiguration {
    @Bean
    PublicPlazaReads publicPlazaReads(
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            ObjectProvider<GameLibrary> games,
            @Value("${poketto.plaza.public-url:${poketto.oauth.issuer:}}") String publicUrl) {
        return new PublicPlazaReads(
                publications,
                new GameContentSnapshots(new WebsiteContentSnapshots(snapshots, publications), games.getIfAvailable()),
                publicUrl);
    }

    @Bean
    QaSources qaSources(PublicPlazaReads reads) {
        return new PlazaQaSources(reads);
    }

    @Bean
    QaCandy qaCandy(JdbcTemplate jdbc) {
        return new QaCandy() {
            @Override
            public void reserve(UUID account) {
                if (jdbc.update("update plaza_wallets set balance=balance-1 where account_id=? and balance>0", account)
                        == 0) {
                    throw new QaException("NO_CANDY", "The well asks for one sweet; try knock");
                }
            }

            @Override
            public void refund(UUID account) {
                if (jdbc.update("update plaza_wallets set balance=balance+1 where account_id=?", account) != 1) {
                    throw new IllegalStateException("A reserved wish has no account wallet");
                }
            }
        };
    }
}
