package io.github.core607.poketto.content.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic GitHub boundary; browser sessions, grant encryption, creation, binding and Git writes are real. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.acceptance.github", havingValue = "true")
public class AcceptanceGitHubConfiguration {
    @Bean
    Fixture acceptanceGitHubFixture(
            JdbcTemplate jdbc,
            @Value("${POKETTO_ACCEPTANCE_ROOT}") String root,
            @Value("${POKETTO_ACCEPTANCE_ORIGIN}") String origin) {
        return new Fixture(jdbc, Path.of(root), origin);
    }

    @Bean
    @Primary
    ManagedGitHubConnections acceptanceGitHubConnections(
            Accounts accounts,
            JdbcTemplate jdbc,
            Fixture fixture,
            @Value("${poketto.repository.credential-key}") String key) {
        var cipher = new GitHubAppGrantCipher(new RepositoryCredentialCipher(key), "Iv.acceptance");
        var store = new GitHubAppGrantStore(jdbc, cipher, "Iv.acceptance", Clock.systemUTC());
        return new ManagedGitHubConnections(
                accounts,
                store,
                fixture.oauth,
                fixture.repositories,
                null,
                Clock.systemUTC(),
                fixture.installations,
                jdbc,
                mock(ManagedRepositoryConnections.class));
    }

    static final class Fixture {
        final GitHubAppOAuth oauth = mock(GitHubAppOAuth.class);
        final GitHubAppRepositories repositories = mock(GitHubAppRepositories.class);
        final GitHubAppInstallations installations = mock(GitHubAppInstallations.class);
        final Set<Long> selected = ConcurrentHashMap.newKeySet();
        private final Map<Long, GitHubAppRepositories.Repository> created = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong(9000);
        private final GitHubAppRepositories.Owner owner =
                new GitHubAppRepositories.Owner(42, "acceptance-user", "User");

        Fixture(JdbcTemplate jdbc, Path root, String origin) {
            when(oauth.begin()).thenAnswer(call -> new GitHubAppOAuth.Flow(random(), random(), Instant.now()));
            when(oauth.authorization(any())).thenAnswer(call -> {
                GitHubAppOAuth.Flow flow = call.getArgument(0);
                return URI.create(
                        origin + "/api/auth/workspaces/github/callback?state=" + flow.state() + "&code=synthetic");
            });
            when(oauth.exchange(any(), anyString(), anyString()))
                    .thenAnswer(call -> new GitHubAppOAuth.Tokens("ghu_acceptance", null, null, null));
            when(repositories.currentUser(anyString())).thenReturn(owner);
            when(repositories.create(anyLong(), anyString(), any(), anyString(), any()))
                    .thenAnswer(call -> {
                        ((Runnable) call.getArgument(4)).run();
                        UUID workspace = jdbc.queryForObject(
                                "select workspace_id from space_github_creation_attempts where creation_marker=?",
                                UUID.class,
                                call.<UUID>getArgument(2));
                        Path remote = root.resolve("github-" + workspace + ".git");
                        try (Git ignored = Git.init()
                                .setBare(true)
                                .setInitialBranch("main")
                                .setDirectory(remote.toFile())
                                .call()) {}
                        AcceptanceRepositories.register(new WorkspaceId(workspace), remote);
                        var repository = new GitHubAppRepositories.Repository(
                                ids.incrementAndGet(), call.getArgument(1), owner, true, false, false, null);
                        created.put(repository.id(), repository);
                        return repository;
                    });
            when(repositories.known(anyLong(), anyLong(), anyString(), anyString()))
                    .thenAnswer(call -> created.get(call.<Long>getArgument(1)));
            when(installations.find(any(), anyString())).thenAnswer(call -> {
                String name = call.getArgument(1);
                boolean allowed = created.values().stream()
                        .anyMatch(repo -> repo.name().equals(name) && selected.contains(repo.id()));
                if (!allowed) {
                    throw new GitHubConnectionException(GitHubConnectionException.Code.INSTALLATION_REQUIRED);
                }
                return 7L;
            });
            when(installations.issue(anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
                long id = call.getArgument(2);
                if (!selected.contains(id)) {
                    throw new GitHubConnectionException(GitHubConnectionException.Code.INSTALLATION_REQUIRED);
                }
                return new GitHubAppInstallations.Token(
                        "ghs_acceptance", Instant.now().plusSeconds(3600), created.get(id));
            });
            when(installations.settingsUrl(any()))
                    .thenReturn(origin + "/api/auth/workspaces/github/fixture-installation");
        }

        private static String random() {
            return UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        }
    }
}
