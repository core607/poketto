package io.github.core607.poketto.content.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubWebhookException;
import java.util.List;
import java.util.Set;

/** Only revocations have effects; installation and permission additions never restore local access. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GitHubWebhookPayload(
        String action,
        Identity sender,
        Installation installation,
        Identity repository,
        @JsonProperty("repositories_removed") List<Identity> repositoriesRemoved) {
    GitHubWebhookPayload {
        repositoriesRemoved = repositoriesRemoved == null ? null : List.copyOf(repositoriesRemoved);
    }

    static GitHubWebhookPayload read(byte[] body) {
        try {
            return GitHubAppJson.read(body, GitHubWebhookPayload.class);
        } catch (GitHubConnectionException malformed) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.MALFORMED);
        }
    }

    void validate(String event, long appId) {
        if (Set.of("github_app_authorization", "installation", "installation_repositories", "repository")
                .contains(event)) {
            require(action != null && action.matches("[a-z_]{1,64}"));
        }
        if (event.equals("github_app_authorization") && "revoked".equals(action)) {
            require(sender != null);
        } else if (installationRevoked(event)) {
            requireInstallation(appId);
        } else if (repositoriesChanged(event)) {
            requireInstallation(appId);
            require(repositoriesRemoved != null);
        } else if (repositoryRevoked(event)) {
            require(installation != null);
            require(repository != null);
        }
    }

    private void requireInstallation(long appId) {
        require(installation != null);
        require(installation.appId() != null);
        require(installation.appId() == appId);
        require(installation.account() != null);
    }

    boolean installationRevoked(String event) {
        return event.equals("installation") && Set.of("deleted", "suspend").contains(action == null ? "" : action);
    }

    boolean repositoriesChanged(String event) {
        return event.equals("installation_repositories")
                && Set.of("added", "removed").contains(action == null ? "" : action);
    }

    boolean repositoryRevoked(String event) {
        return event.equals("repository")
                && Set.of("deleted", "transferred", "publicized", "archived", "renamed")
                        .contains(action == null ? "" : action);
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.MALFORMED);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Identity(long id) {
        Identity {
            require(id > 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Installation(long id, @JsonProperty("app_id") Long appId, Identity account) {
        Installation {
            require(id > 0);
        }
    }
}
