package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.GitHubConnectionException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitHubAppRepositoriesTests {
    private static final UUID MARKER = UUID.fromString("86e91eaa-bfcd-4ca3-9bf8-177cceef0fca");
    private static final String USER = "{\"id\":42,\"login\":\"octocat\",\"type\":\"User\"}";

    @Test
    void createsOnlyInVerifiedPersonalAccountWithPrivateVisibilityAndDurableMarker() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER);
            fixture.reply(201, repository(GitHubAppRepositories.creationDescription(MARKER)));
            var api = new GitHubAppRepositories(fixture.http);
            assertThat(api.create(42, "notes", MARKER, "fixture-user-token").id())
                    .isEqualTo(91);
            assertThat(fixture.requests)
                    .extracting(GitHubAppFixture.Request::path)
                    .containsExactly("/user", "/user/repos");
            GitHubAppFixture.Request request = fixture.requests.get(1);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.authorization()).isEqualTo("Bearer fixture-user-token");
            JsonNode body = JsonMapper.builder().build().readTree(request.body());
            assertThat(body.size()).isEqualTo(4);
            assertThat(body.path("name").asText()).isEqualTo("notes");
            assertThat(body.path("private").booleanValue()).isTrue();
            assertThat(body.path("auto_init").booleanValue()).isFalse();
            assertThat(body.path("description").asText()).isEqualTo(GitHubAppRepositories.creationDescription(MARKER));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Organization", "Bot"})
    void rejectsNonPersonalIdentityBeforeMutation(String type) throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER.replace("User", type));
            var api = new GitHubAppRepositories(fixture.http);
            assertThatThrownBy(() -> api.create(42, "notes", MARKER, "fixture-token"))
                    .hasMessage("GitHub App: IDENTITY_CHANGED");
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @Test
    void refusesChangedAccountAndInvalidNamesBeforeCreating() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            var api = new GitHubAppRepositories(fixture.http);
            assertThatThrownBy(() -> api.create(42, "../other", MARKER, "fixture-token"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.requests).isEmpty();
            fixture.reply(200, USER);
            assertThatThrownBy(() -> api.create(43, "notes", MARKER, "fixture-token"))
                    .hasMessage("GitHub App: IDENTITY_CHANGED");
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 200, 302, 403, 429, 500, 503})
    void ambiguousCreationNeverRetries(int status) throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER);
            fixture.reply(status, "{\"message\":\"fixture-secret-error\"}");
            var api = new GitHubAppRepositories(fixture.http);
            assertThatThrownBy(() -> api.create(42, "notes", MARKER, "fixture-token"))
                    .hasMessage("GitHub App: CREATION_UNCERTAIN");
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 422})
    void definitiveRejectionIsDistinctFromLostResponse(int status) throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER);
            fixture.reply(status, "{\"message\":\"fixture-secret-error\"}");
            var api = new GitHubAppRepositories(fixture.http);
            assertThatThrownBy(() -> api.create(42, "notes", MARKER, "fixture-token"))
                    .hasMessage("GitHub App: CREATION_REJECTED");
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"null", "{}", "{\"id\":\"fixture-secret\"}", "{\"id\":1.5}", "{\"id\":1,\"id\":2}", "{} {}"})
    void malformedSuccessfulCreationRemainsUncertainWithoutLeakingProviderBody(String body) throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER);
            fixture.reply(201, body);
            var api = new GitHubAppRepositories(fixture.http);
            assertThatThrownBy(() -> api.create(42, "notes", MARKER, "fixture-token"))
                    .isInstanceOfSatisfying(GitHubConnectionException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(GitHubConnectionException.Code.CREATION_UNCERTAIN);
                        var trace = new StringWriter();
                        failure.printStackTrace(new PrintWriter(trace));
                        assertThat(trace.toString()).doesNotContain("fixture-secret");
                    });
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @Test
    void recoveryRequiresExactMarkerAndDoesNotAdoptSameNameRepository() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            var api = new GitHubAppRepositories(fixture.http);
            fixture.reply(200, USER);
            fixture.reply(200, repository("existing user repository"));
            assertThatThrownBy(() -> api.reconcile(42, "notes", MARKER, "fixture-token"))
                    .hasMessage("GitHub App: REPOSITORY_CHANGED");
            fixture.reply(200, USER);
            fixture.reply(200, repository(GitHubAppRepositories.creationDescription(MARKER)));
            assertThat(api.reconcile(42, "notes", MARKER, "fixture-token"))
                    .get()
                    .extracting(GitHubAppRepositories.Repository::id)
                    .isEqualTo(91L);
            assertThat(fixture.requests).allMatch(request -> request.method().equals("GET"));
        }
    }

    @Test
    void missingRepositoryDuringRecoveryDoesNotCreateOne() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER);
            fixture.reply(404, "{}");
            var api = new GitHubAppRepositories(fixture.http);
            assertThat(api.reconcile(42, "notes", MARKER, "fixture-token")).isEmpty();
            assertThat(fixture.requests)
                    .extracting(GitHubAppFixture.Request::path)
                    .containsExactly("/user", "/repos/octocat/notes");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "private", "name", "missing-private"})
    void doesNotTrustMismatchedCreationResponse(String variant) throws Exception {
        String body = repository("description");
        body = switch (variant) {
            case "owner" -> body.replace("\"id\":42", "\"id\":43");
            case "private" -> body.replace("\"private\":true", "\"private\":false");
            case "name" -> body.replace("notes", "different");
            default -> body.replace("\"private\":true,", "");
        };
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, USER);
            fixture.reply(201, body);
            var api = new GitHubAppRepositories(fixture.http);
            assertThatThrownBy(() -> api.create(42, "notes", MARKER, "fixture-token"))
                    .hasMessage("GitHub App: CREATION_UNCERTAIN");
        }
    }

    private static String repository(String description) {
        return "{\"id\":91,\"name\":\"notes\",\"owner\":" + USER
                + ",\"private\":true,\"archived\":false,\"disabled\":false,\"description\":\"" + description + "\"}";
    }
}
