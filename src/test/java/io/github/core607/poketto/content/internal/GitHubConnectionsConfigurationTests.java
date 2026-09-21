package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.content.GitHubConnections;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GitHubConnectionsConfigurationTests {
    private static String signingKey;
    private static final String CIPHER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String WEBHOOK = "fixture-webhook-secret-at-least-32-characters";
    private final Accounts accounts = mock(Accounts.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GitHubConnectionsConfiguration configuration = new GitHubConnectionsConfiguration();

    @BeforeAll
    static void key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    }

    @BeforeEach
    void account() {
        UUID id = UUID.randomUUID();
        when(actor.accountId()).thenReturn(id);
        when(accounts.account(actor)).thenReturn(new AccountIdentity(id, "creator", "Creator", SiteGroup.CREATOR));
    }

    @Test
    void absentAndPartialConfigurationDisableOnlyTheGitHubEntrance() {
        try (var absent = configuration.githubConnections(
                        accounts, jdbc, mock(ManagedRepositoryConnections.class), "", "", "", "", "", "", "");
                var partial = configuration.githubConnections(
                        accounts,
                        jdbc,
                        mock(ManagedRepositoryConnections.class),
                        "3",
                        "Iv.fixture",
                        "",
                        "",
                        "",
                        CIPHER_KEY,
                        "https://example.test")) {
            assertThat(absent.status(actor).state()).isEqualTo(GitHubConnections.State.DISABLED);
            assertThat(partial.status(actor).available()).isFalse();
            assertThat(partial.status(actor).eligibleToCreate()).isTrue();
            assertThatThrownBy(() -> partial.begin(actor)).hasMessage("GitHub App: UNAVAILABLE");
        }
    }

    @Test
    void completeConfigurationCreatesTheExactCallbackWithoutExposingClientCredentials() {
        try (var connections = configuration.githubConnections(
                accounts,
                jdbc,
                mock(ManagedRepositoryConnections.class),
                "3",
                "Iv.fixture",
                "fixture-secret",
                signingKey,
                WEBHOOK,
                CIPHER_KEY,
                "https://example.test")) {
            GitHubConnections.Authorization authorization = connections.begin(actor);
            assertThat(authorization.url())
                    .startsWith("https://github.com/login/oauth/authorize?")
                    .contains("redirect_uri=https%3A%2F%2Fexample.test%2Fapi%2Fauth%2Fworkspaces%2Fgithub%2Fcallback")
                    .doesNotContain("fixture-secret", authorization.verifier());
            assertThat(authorization.toString()).doesNotContain(authorization.state(), authorization.verifier());
        }
    }

    @Test
    void malformedCompleteConfigurationNeverUsesAnUntrustedCallbackOrLeaksItsSecrets() {
        assertThatThrownBy(() -> configuration.githubConnections(
                        accounts,
                        jdbc,
                        mock(ManagedRepositoryConnections.class),
                        "3",
                        "Iv.fixture",
                        "fixture-secret",
                        signingKey,
                        WEBHOOK,
                        CIPHER_KEY,
                        "https://example.test/elsewhere"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("fixture-secret")
                .hasMessageNotContaining(signingKey);
        assertThatThrownBy(() -> configuration.githubConnections(
                        accounts,
                        jdbc,
                        mock(ManagedRepositoryConnections.class),
                        "3",
                        "Iv.fixture",
                        "secret\ninvalid",
                        signingKey,
                        WEBHOOK,
                        CIPHER_KEY,
                        "https://example.test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret\ninvalid")
                .hasMessageNotContaining(signingKey);
    }
}
