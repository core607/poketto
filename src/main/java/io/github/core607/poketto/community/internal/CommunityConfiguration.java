package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class CommunityConfiguration {
    @Bean
    Community community(
            JdbcTemplate jdbc,
            Accounts accounts,
            CommunityAccounts communityAccounts,
            PublicationGuard publicationGuard,
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            PlatformTransactionManager transactions,
            ObjectMapper json) {
        var scope = new CommunityScope(transactions, communityAccounts, publicationGuard, snapshots);
        var targets = new CommunityTargets(publications, snapshots);
        var activity = new CommunityActivity(jdbc, Clock.systemUTC());
        var relations = new CommunityRelations(jdbc, scope, targets, activity);
        var comments = new CommunityComments(jdbc, communityAccounts, scope, targets, activity);
        var feeds = new CommunityFeeds(jdbc, scope, targets, json);
        var inbox = new CommunityInbox(jdbc, scope, targets, comments, communityAccounts, activity);
        var moderation = new CommunityModeration(jdbc, scope, communityAccounts, comments, activity);
        return new JdbcCommunity(accounts, targets, relations, comments, feeds, inbox, moderation);
    }
}
