package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.GitHubConnectionException.Code.INSTALLATION_REQUIRED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.INVALID_RESPONSE;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.REPOSITORY_CHANGED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.UNAVAILABLE;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.content.GitHubConnectionException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Verifies personal installations and narrows each Git credential to one immutable repository. */
final class GitHubAppInstallations {
    private final GitHubAppHttp http;
    private final GitHubAppSigner signer;
    private final long appId;
    private final Clock clock;

    GitHubAppInstallations(GitHubAppHttp http, GitHubAppSigner signer, long appId, Clock clock) {
        if (appId <= 0) {
            throw new IllegalArgumentException("GitHub App ID must be positive");
        }
        this.http = http;
        this.signer = signer;
        this.appId = appId;
        this.clock = Objects.requireNonNull(clock, "Installation token clock is required");
    }

    long find(GitHubAppRepositories.Owner owner, String repositoryName) {
        GitHubAppRepositories.requirePersonal(owner);
        GitHubAppRepositories.requireName(repositoryName);
        GitHubAppHttp.Reply reply = get("/repos/" + owner.login() + "/" + repositoryName + "/installation");
        requireStatus(reply, 200);
        Installation installation = GitHubAppJson.read(reply.body(), Installation.class);
        requireInstallation(installation, owner.id());
        return installation.id();
    }

    Token issue(long installationId, long ownerId, long repositoryId) {
        requireIdentifier(installationId);
        requireIdentifier(ownerId);
        requireIdentifier(repositoryId);
        GitHubAppHttp.Reply metadata = get("/app/installations/" + installationId);
        requireStatus(metadata, 200);
        Installation installation = GitHubAppJson.read(metadata.body(), Installation.class);
        if (installation.id() != installationId) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        requireInstallation(installation, ownerId);
        var request = new TokenRequest(List.of(repositoryId), new Permissions("write", "read"));
        Instant requestedAt = clock.instant();
        GitHubAppHttp.Reply reply;
        try {
            reply = http.postApi(
                    "/app/installations/" + installationId + "/access_tokens",
                    signer.token(),
                    GitHubAppJson.write(request));
        } catch (IOException failure) {
            throw new GitHubConnectionException(UNAVAILABLE, failure);
        }
        requireStatus(reply, 201);
        TokenResponse response = GitHubAppJson.read(reply.body(), TokenResponse.class);
        requireScope(response, ownerId, repositoryId, requestedAt);
        return new Token(
                response.token(), response.expiresAt(), response.repositories().getFirst());
    }

    private void requireScope(TokenResponse response, long ownerId, long repositoryId, Instant requestedAt) {
        if (!response.permissions().equals(Map.of("contents", "write", "metadata", "read"))) {
            throw new GitHubConnectionException(INVALID_RESPONSE);
        }
        if (response.repositories().size() != 1) {
            throw new GitHubConnectionException(INVALID_RESPONSE);
        }
        GitHubAppRepositories.Repository repository = response.repositories().getFirst();
        if (repository.id() != repositoryId) {
            throw new GitHubConnectionException(REPOSITORY_CHANGED);
        }
        GitHubAppRepositories.requireOwnedPrivate(repository, ownerId);
        if (!response.expiresAt().isAfter(clock.instant().plusSeconds(30))) {
            throw new GitHubConnectionException(INVALID_RESPONSE);
        }
        if (response.expiresAt().isAfter(requestedAt.plusSeconds(3700))) {
            throw new GitHubConnectionException(INVALID_RESPONSE);
        }
    }

    private void requireInstallation(Installation installation, long ownerId) {
        if (installation.appId() != appId) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        if (installation.account().id() != ownerId || installation.targetId() != ownerId) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        if (!installation.account().type().equals("User")
                || !installation.targetType().equals("User")) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        if (installation.suspendedAt() != null) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        if (!"write".equals(installation.permissions().get("contents"))) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        if (!"read".equals(installation.permissions().get("metadata"))) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
    }

    private GitHubAppHttp.Reply get(String path) {
        try {
            return http.getApi(path, signer.token());
        } catch (IOException failure) {
            throw new GitHubConnectionException(UNAVAILABLE, failure);
        }
    }

    private static void requireStatus(GitHubAppHttp.Reply reply, int expected) {
        if (reply.status() == 404 || reply.status() == 422) {
            throw new GitHubConnectionException(INSTALLATION_REQUIRED);
        }
        if (reply.status() != expected) {
            throw new GitHubConnectionException(UNAVAILABLE);
        }
    }

    private static void requireIdentifier(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("GitHub identity must be positive");
        }
    }

    record Token(String value, Instant expiresAt, GitHubAppRepositories.Repository repository) {
        @Override
        public String toString() {
            return "GitHubInstallationToken[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Installation(
            long id,
            GitHubAppRepositories.Owner account,
            @JsonProperty("app_id") long appId,
            @JsonProperty("target_id") long targetId,
            @JsonProperty("target_type") String targetType,
            @JsonProperty("suspended_at") Instant suspendedAt,
            Map<String, String> permissions) {
        Installation {
            GitHubAppJson.require(id > 0);
            GitHubAppJson.require(account != null);
            GitHubAppJson.require(appId > 0);
            GitHubAppJson.require(targetId > 0);
            GitHubAppJson.require(targetType != null);
            GitHubAppJson.require(permissions != null);
            permissions = Map.copyOf(permissions);
        }

        @Override
        public String toString() {
            return "GitHubInstallation[id=" + id + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            String token,
            @JsonProperty("expires_at") Instant expiresAt,
            Map<String, String> permissions,
            List<GitHubAppRepositories.Repository> repositories) {
        TokenResponse {
            GitHubAppJson.require(token != null && token.length() <= 4096 && token.matches("[A-Za-z0-9._~+/-]+={0,2}"));
            GitHubAppJson.require(expiresAt != null);
            GitHubAppJson.require(permissions != null);
            GitHubAppJson.require(repositories != null);
            permissions = Map.copyOf(permissions);
            repositories = List.copyOf(repositories);
        }

        @Override
        public String toString() {
            return "GitHubInstallationTokenResponse[redacted]";
        }
    }

    private record TokenRequest(
            @JsonProperty("repository_ids") List<Long> repositoryIds, Permissions permissions) {}

    private record Permissions(String contents, String metadata) {}
}
