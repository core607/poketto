package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.GitHubConnectionException.Code.AUTHORIZATION_REQUIRED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.CREATION_REJECTED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.CREATION_UNCERTAIN;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.IDENTITY_CHANGED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.REPOSITORY_CHANGED;
import static io.github.core607.poketto.content.GitHubConnectionException.Code.UNAVAILABLE;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.core607.poketto.content.GitHubConnectionException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Personal-account creation only. Its caller must durably record intent before invoking create. */
final class GitHubAppRepositories {
    private final GitHubAppHttp http;

    GitHubAppRepositories(GitHubAppHttp http) {
        this.http = http;
    }

    Owner currentUser(String userToken) {
        GitHubAppHttp.Reply reply = get("/user", userToken);
        requireReadable(reply);
        Owner owner = GitHubAppJson.read(reply.body(), Owner.class);
        requirePersonal(owner);
        return owner;
    }

    Repository create(long expectedOwner, String name, UUID marker, String userToken, Runnable beforeCreate) {
        requireName(name);
        String description = creationDescription(marker);
        Owner owner = currentUser(userToken);
        if (owner.id() != expectedOwner) {
            throw new GitHubConnectionException(IDENTITY_CHANGED);
        }
        byte[] body = GitHubAppJson.write(new CreateRequest(name, true, false, description));
        beforeCreate.run();
        GitHubAppHttp.Reply reply;
        try {
            reply = http.postApi("/user/repos", userToken, body);
        } catch (IOException failure) {
            throw new GitHubConnectionException(CREATION_UNCERTAIN, failure);
        }
        requireCreated(reply);
        try {
            Repository repository = GitHubAppJson.read(reply.body(), Repository.class);
            requireOwnedPrivate(repository, expectedOwner);
            if (!repository.name().equalsIgnoreCase(name)) {
                throw new GitHubConnectionException(REPOSITORY_CHANGED);
            }
            return repository;
        } catch (GitHubConnectionException failure) {
            // A successful mutation with an unusable answer must never become a retryable rejection.
            throw new GitHubConnectionException(CREATION_UNCERTAIN, failure);
        }
    }

    /** Absence is not evidence that a previous POST failed. This method never creates anything. */
    Optional<Repository> reconcile(long ownerId, String name, UUID marker, String userToken) {
        requireName(name);
        String expectedDescription = creationDescription(marker);
        Owner owner = currentUser(userToken);
        if (owner.id() != ownerId) {
            throw new GitHubConnectionException(IDENTITY_CHANGED);
        }
        GitHubAppHttp.Reply reply = get("/repos/" + owner.login() + "/" + name, userToken);
        if (reply.status() == 404) {
            return Optional.empty();
        }
        requireReadable(reply);
        Repository repository = GitHubAppJson.read(reply.body(), Repository.class);
        requireOwnedPrivate(repository, ownerId);
        if (!repository.name().equalsIgnoreCase(name)) {
            throw new GitHubConnectionException(REPOSITORY_CHANGED);
        }
        if (!expectedDescription.equals(repository.description())) {
            throw new GitHubConnectionException(REPOSITORY_CHANGED);
        }
        return Optional.of(repository);
    }

    static String creationDescription(UUID marker) {
        return "Poketto personal space [creation:" + Objects.requireNonNull(marker, "Creation marker is required")
                + "]";
    }

    static void requireOwnedPrivate(Repository repository, long ownerId) {
        requirePersonal(repository.owner());
        if (repository.owner().id() != ownerId) {
            throw new GitHubConnectionException(REPOSITORY_CHANGED);
        }
        if (!repository.privateRepository()) {
            throw new GitHubConnectionException(REPOSITORY_CHANGED);
        }
        if (repository.archived() || repository.disabled()) {
            throw new GitHubConnectionException(UNAVAILABLE);
        }
    }

    static void requirePersonal(Owner owner) {
        if (!owner.type().equals("User")) {
            throw new GitHubConnectionException(IDENTITY_CHANGED);
        }
    }

    static void requireName(String name) {
        if (!validName(name)) {
            throw new IllegalArgumentException("GitHub repository name must contain 1-100 ASCII name characters");
        }
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,100}") && !name.equals(".") && !name.equals("..");
    }

    private GitHubAppHttp.Reply get(String path, String userToken) {
        try {
            return http.getApi(path, userToken);
        } catch (IOException failure) {
            throw new GitHubConnectionException(UNAVAILABLE, failure);
        }
    }

    private static void requireReadable(GitHubAppHttp.Reply reply) {
        if (reply.status() == 401) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        if (reply.status() != 200) {
            throw new GitHubConnectionException(UNAVAILABLE);
        }
    }

    private static void requireCreated(GitHubAppHttp.Reply reply) {
        if (reply.status() == 201) {
            return;
        }
        if (reply.status() == 401) {
            throw new GitHubConnectionException(AUTHORIZATION_REQUIRED);
        }
        if (reply.status() == 400 || reply.status() == 422) {
            throw new GitHubConnectionException(CREATION_REJECTED);
        }
        // Rate limits and redirects do not prove whether the mutation was applied.
        throw new GitHubConnectionException(CREATION_UNCERTAIN);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Owner(long id, String login, String type) {
        Owner {
            GitHubAppJson.require(id > 0);
            GitHubAppJson.require(login != null && login.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}"));
            GitHubAppJson.require(type != null && !type.isBlank());
        }

        @Override
        public String toString() {
            return "GitHubOwner[id=" + id + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Repository(
            long id,
            String name,
            Owner owner,
            @JsonProperty("private") Boolean privateRepository,
            Boolean archived,
            Boolean disabled,
            String description) {
        Repository {
            GitHubAppJson.require(id > 0);
            GitHubAppJson.require(validName(name));
            GitHubAppJson.require(owner != null);
            GitHubAppJson.require(privateRepository != null);
            GitHubAppJson.require(archived != null);
            GitHubAppJson.require(disabled != null);
        }

        @Override
        public String toString() {
            return "GitHubRepository[id=" + id + "]";
        }
    }

    private record CreateRequest(
            String name,
            @JsonProperty("private") boolean privateRepository,
            @JsonProperty("auto_init") boolean autoInit,
            String description) {}
}
