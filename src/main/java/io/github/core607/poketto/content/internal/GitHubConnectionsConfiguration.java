package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.content.GitHubWebhookException;
import io.github.core607.poketto.content.GitHubWebhooks;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class GitHubConnectionsConfiguration {
    @Bean
    GitHubWebhooks githubWebhooks(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            @Value("${poketto.github.app-id:}") String appId,
            @Value("${poketto.github.client-id:}") String clientId,
            @Value("${poketto.github.webhook-secret:}") String secret) {
        if (List.of(appId, clientId, secret).stream().anyMatch(String::isBlank)) {
            return (delivery, event, signature, body) -> {
                throw new GitHubWebhookException(GitHubWebhookException.Code.UNAVAILABLE);
            };
        }
        requireAppId(appId);
        if (!clientId.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("GitHub App client ID is invalid");
        }
        return new GitHubAppWebhooks(
                Long.parseLong(appId), secret, new GitHubWebhookStore(jdbc, transactions, clientId));
    }

    @Bean(destroyMethod = "close")
    ManagedGitHubConnections githubConnections(
            Accounts accounts,
            JdbcTemplate jdbc,
            ManagedRepositoryConnections manual,
            @Value("${poketto.github.app-id:}") String appId,
            @Value("${poketto.github.client-id:}") String clientId,
            @Value("${poketto.github.client-secret:}") String clientSecret,
            @Value("${poketto.github.private-key:}") String privateKey,
            @Value("${poketto.github.webhook-secret:}") String webhookSecret,
            @Value("${poketto.repository.credential-key:}") String credentialKey,
            @Value("${poketto.oauth.issuer:}") String origin) {
        var appFields = List.of(appId, clientId, clientSecret, privateKey, webhookSecret);
        if (appFields.stream().anyMatch(String::isBlank) || credentialKey.isBlank() || origin.isBlank()) {
            return ManagedGitHubConnections.disabled(accounts);
        }
        requireAppId(appId);
        if (webhookSecret.length() < 32 || webhookSecret.length() > 256) {
            throw new IllegalArgumentException("GitHub webhook secret must contain 32-256 characters");
        }
        var cipher = new RepositoryCredentialCipher(credentialKey);
        if (!cipher.available()) {
            throw new IllegalArgumentException(
                    "GitHub authorization requires the repository credential encryption key");
        }
        Clock clock = Clock.systemUTC();
        var signer = new GitHubAppSigner(clientId, privateKey, clock);
        GitHubAppOAuth.validateClientSecret(clientSecret);
        URI callback = callback(origin);
        var http = new GitHubAppHttp();
        var oauth = new GitHubAppOAuth(http, clientId, clientSecret, callback, clock);
        var store = new GitHubAppGrantStore(jdbc, new GitHubAppGrantCipher(cipher, clientId), clientId, clock);
        return new ManagedGitHubConnections(
                accounts,
                store,
                oauth,
                new GitHubAppRepositories(http),
                http,
                clock,
                new GitHubAppInstallations(http, signer, Long.parseLong(appId), clock),
                jdbc,
                manual);
    }

    @Bean
    GitHubRepositoryBindings githubRepositoryBindings(JdbcTemplate jdbc, ManagedGitHubConnections github) {
        return new GitHubRepositoryBindings(jdbc, github);
    }

    private static void requireAppId(String id) {
        if (!id.matches("[1-9][0-9]{0,17}")) {
            throw new IllegalArgumentException("GitHub App ID must be a positive integer of at most 18 digits");
        }
    }

    private static URI callback(String origin) {
        URI uri = URI.create(origin);
        if (!validOrigin(uri)) {
            throw new IllegalArgumentException("GitHub App authorization requires a public HTTPS origin");
        }
        return uri.resolve("/api/auth/workspaces/github/callback");
    }

    private static boolean validOrigin(URI uri) {
        return "https".equals(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null
                && (uri.getPath().isEmpty() || uri.getPath().equals("/"));
    }
}
