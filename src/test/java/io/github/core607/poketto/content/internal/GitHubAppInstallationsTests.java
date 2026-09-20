package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GitHubAppInstallationsTests {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OWNER = "{\"id\":42,\"login\":\"octocat\",\"type\":\"User\"}";
    private static final String REPOSITORY = "{\"id\":91,\"name\":\"notes\",\"owner\":" + OWNER
            + ",\"private\":true,\"archived\":false,\"disabled\":false}";
    private static final String INSTALLATION =
            "{\"id\":7,\"app_id\":3,\"target_id\":42,\"target_type\":\"User\",\"account\":" + OWNER
                    + ",\"suspended_at\":null,\"permissions\":{\"contents\":\"write\",\"metadata\":\"read\",\"administration\":\"write\"}}";
    private static final String TOKEN =
            "{\"token\":\"ghs_3_fixture.jwt.token\",\"expires_at\":\"2026-09-21T01:00:00Z\","
                    + "\"permissions\":{\"contents\":\"write\",\"metadata\":\"read\"},\"repositories\":[" + REPOSITORY
                    + "]}";
    private static GitHubAppSigner signer;

    @BeforeAll
    static void signingKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        signer = new GitHubAppSigner("Iv.fixture", key, CLOCK);
    }

    @Test
    void findsPersonalInstallationAndIssuesExactlyOneRepositoryWithOnlyGitPermissions() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, INSTALLATION);
            fixture.reply(200, INSTALLATION);
            fixture.reply(201, TOKEN);
            var api = api(fixture);
            assertThat(api.find(GitHubAppJson.read(REPOSITORY.getBytes(), GitHubAppRepositories.Repository.class)))
                    .isEqualTo(7);
            GitHubAppInstallations.Token token = api.issue(7, 42, 91);
            assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
            assertThat(token.repository().id()).isEqualTo(91);
            assertThat(token.toString()).doesNotContain(token.value());
            assertThat(fixture.requests)
                    .extracting(GitHubAppFixture.Request::path)
                    .containsExactly(
                            "/repos/octocat/notes/installation",
                            "/app/installations/7",
                            "/app/installations/7/access_tokens");
            assertThat(fixture.requests)
                    .allMatch(request -> request.authorization().equals("Bearer " + signer.token()));
            JsonNode request = JsonMapper.builder()
                    .build()
                    .readTree(fixture.requests.get(2).body());
            assertThat(request.size()).isEqualTo(2);
            assertThat(request.path("repository_ids").size()).isEqualTo(1);
            assertThat(request.path("repository_ids").get(0).asLong()).isEqualTo(91);
            assertThat(request.path("permissions").size()).isEqualTo(2);
            assertThat(request.path("permissions").path("contents").asText()).isEqualTo("write");
            assertThat(request.path("permissions").path("metadata").asText()).isEqualTo("read");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "id", "account", "target", "organization", "suspended", "permissions"})
    void refusesWrongOrSuspendedInstallationBeforeIssuingToken(String variant) throws Exception {
        String metadata =
                switch (variant) {
                    case "app" -> INSTALLATION.replace("\"app_id\":3", "\"app_id\":4");
                    case "id" -> INSTALLATION.replace("\"id\":7", "\"id\":8");
                    case "account" -> INSTALLATION.replace("\"id\":42", "\"id\":43");
                    case "target" -> INSTALLATION.replace("\"target_id\":42", "\"target_id\":43");
                    case "organization" -> INSTALLATION.replace("User", "Organization");
                    case "suspended" ->
                        INSTALLATION.replace("\"suspended_at\":null", "\"suspended_at\":\"2026-09-20T00:00:00Z\"");
                    default -> INSTALLATION.replace("\"contents\":\"write\"", "\"contents\":\"read\"");
                };
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, metadata);
            var api = api(fixture);
            assertThatThrownBy(() -> api.issue(7, 42, 91)).hasMessage("GitHub App: INSTALLATION_REQUIRED");
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra-repo", "no-repo", "extra-permission", "read-only", "expired", "long-lived"})
    void neverReturnsAnOverbroadOrInvalidCredential(String variant) throws Exception {
        String response =
                switch (variant) {
                    case "extra-repo" -> TOKEN.replace(REPOSITORY, REPOSITORY + "," + REPOSITORY);
                    case "no-repo" -> TOKEN.replace(REPOSITORY, "");
                    case "extra-permission" ->
                        TOKEN.replace("\"metadata\":\"read\"", "\"metadata\":\"read\",\"administration\":\"write\"");
                    case "read-only" -> TOKEN.replace("\"contents\":\"write\"", "\"contents\":\"read\"");
                    case "expired" -> TOKEN.replace("01:00:00Z", "00:00:00Z");
                    default -> TOKEN.replace("01:00:00Z", "02:00:00Z");
                };
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, INSTALLATION);
            fixture.reply(201, response);
            var api = api(fixture);
            assertThatThrownBy(() -> api.issue(7, 42, 91)).hasMessage("GitHub App: INVALID_RESPONSE");
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "repository", "public"})
    void catchesRepositoryTransferReplacementAndVisibilityChanges(String variant) throws Exception {
        String response =
                switch (variant) {
                    case "owner" -> TOKEN.replace("\"id\":42", "\"id\":43");
                    case "repository" -> TOKEN.replace("\"id\":91", "\"id\":92");
                    default -> TOKEN.replace("\"private\":true", "\"private\":false");
                };
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(200, INSTALLATION);
            fixture.reply(201, response);
            var api = api(fixture);
            assertThatThrownBy(() -> api.issue(7, 42, 91)).hasMessage("GitHub App: REPOSITORY_CHANGED");
        }
    }

    @Test
    void absentRepositoryAccessRequiresInstallationSelectionAndNeverExpandsAccess() throws Exception {
        try (var fixture = new GitHubAppFixture()) {
            fixture.reply(404, "{}");
            var api = api(fixture);
            assertThatThrownBy(() ->
                            api.find(GitHubAppJson.read(REPOSITORY.getBytes(), GitHubAppRepositories.Repository.class)))
                    .hasMessage("GitHub App: INSTALLATION_REQUIRED");
            assertThat(fixture.requests).hasSize(1);
            assertThat(fixture.requests.getFirst().method()).isEqualTo("GET");
        }
    }

    private static GitHubAppInstallations api(GitHubAppFixture fixture) {
        return new GitHubAppInstallations(fixture.http, signer, 3, CLOCK);
    }
}
