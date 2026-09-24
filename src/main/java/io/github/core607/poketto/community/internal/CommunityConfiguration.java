package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.Readership;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.ReviewedBodyEdits;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
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
    CommunityCorrections corrections(
            JdbcTemplate jdbc,
            AuthService auth,
            CommunityAccounts communityAccounts,
            PublicationGuard publicationGuard,
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            PlatformTransactionManager transactions,
            ReviewedBodyEdits edits) {
        return new CommunityCorrections(
                jdbc,
                auth,
                communityAccounts,
                new CommunityScope(transactions, communityAccounts, publicationGuard, snapshots),
                new CommunityTargets(publications, snapshots),
                new CommunityActivity(jdbc, Clock.systemUTC()),
                edits);
    }

    @Bean
    Community community(
            JdbcTemplate jdbc,
            Accounts accounts,
            CommunityAccounts communityAccounts,
            PublicationGuard publicationGuard,
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            PlatformTransactionManager transactions,
            ObjectMapper json,
            CommunityCorrections corrections) {
        var scope = new CommunityScope(transactions, communityAccounts, publicationGuard, snapshots);
        var targets = new CommunityTargets(publications, snapshots);
        var activity = new CommunityActivity(jdbc, Clock.systemUTC());
        var relations = new CommunityRelations(jdbc, scope, targets, activity);
        var comments = new CommunityComments(jdbc, communityAccounts, scope, targets, activity);
        var feeds = new CommunityFeeds(jdbc, scope, targets, json);
        var inbox = new CommunityInbox(jdbc, scope, targets, comments, corrections, communityAccounts, activity);
        var moderation = new CommunityModeration(jdbc, scope, communityAccounts, comments, activity);
        return new JdbcCommunity(accounts, targets, relations, comments, feeds, inbox, moderation);
    }

    @Bean
    Readership readership(
            JdbcTemplate jdbc,
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            @Value("${poketto.community.reader-capacity:100000}") int capacity,
            @Value("${poketto.community.reader-reports-per-address:300}") int perAddress) {
        var random = new SecureRandom();
        var seen = new ReaderDigests(capacity, perAddress, () -> {
            byte[] salt = new byte[32];
            random.nextBytes(salt);
            return salt;
        });
        return new JdbcReadership(jdbc, new CommunityTargets(publications, snapshots), seen, Clock.systemUTC());
    }
}
