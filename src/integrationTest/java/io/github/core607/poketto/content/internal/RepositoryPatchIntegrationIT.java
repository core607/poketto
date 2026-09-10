package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@Import(RemoteRepositoryIntegrationConfiguration.class)
class RepositoryPatchIntegrationIT {
    @TempDir
    static Path data;

    private static final String INITIALIZATION = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Path remote = data.resolve("remote.git");
        try (Git ignored = Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(remote.toFile())
                .call()) {
            registry.add(
                    "poketto.test.repository-path",
                    () -> remote.toAbsolutePath().toString());
        } catch (Exception exception) {
            throw new IllegalStateException("synthetic Git remote could not be prepared", exception);
        }
        registry.add("poketto.data-dir", () -> data.toAbsolutePath().toString());
        registry.add("poketto.auth.initialization-token", () -> INITIALIZATION);
    }

    @Autowired
    AuthService auth;

    @Autowired
    WorkspaceCatalog workspaces;

    @Autowired
    RepositoryPatchService patches;

    @Autowired
    RepositoryMoveService moves;

    @Autowired
    RepositoryContentReader files;

    @Autowired
    PublicContentSnapshots snapshots;

    @Test
    void liveKeyPermissionsAndGitAcknowledgementControlThePublicSnapshot() {
        var workspace = workspaces.defaultWorkspace().id();
        var owner =
                auth.initializeOwner(INITIALIZATION, "owner", UUID.randomUUID().toString());
        var issued = auth.createApiKey(owner, workspace, owner.accountId(), null);
        var agent = auth.authenticateApiKey(issued.token());
        var draft = patches.apply(
                agent,
                workspace,
                new RepositoryPatch(Optional.empty(), List.of(create("private/draft.md", "# Draft"))));
        assertThat(draft.committed()).isTrue();
        assertThat(draft.snapshotUpdated()).isTrue();
        assertThat(snapshots.current(workspace).articles()).isEmpty();
        String policy = "enabled: true\nmode: public-root\n";
        var publish = new RepositoryPatch(
                Optional.of(draft.commit()),
                List.of(create(RepositoryPublishingPolicy.PATH, policy), create("public/article.md", "# Public")));
        assertThatThrownBy(() -> patches.apply(agent, workspace, publish)).isInstanceOf(AuthException.class);
        var published = patches.apply(owner, workspace, publish);
        assertThat(published.snapshotUpdated()).isTrue();
        assertThat(snapshots.current(workspace).commit()).contains(published.commit());
        assertThat(snapshots.current(workspace).articles()).hasSize(1);

        var before = files.getFile(workspace, Optional.of(published.commit()), "private/draft.md");
        var privateEdit = new RepositoryPatch(
                Optional.of(published.commit()),
                List.of(new RepositoryTextChange(
                        before.path(),
                        false,
                        before.revision(),
                        Optional.of("---\nroute: /article\n---\n# Private alias"))));
        var edited = patches.apply(agent, workspace, privateEdit);
        assertThat(snapshots.current(workspace).commit()).contains(edited.commit());
        assertThat(snapshots.current(workspace).articles()).hasSize(1);
        assertThat(snapshots.current(workspace).articles().getFirst().title()).isEqualTo("Public");

        var relocated = moves.move(
                agent,
                workspace,
                new RepositoryMoveRequest(edited.commit(), "private/draft.md", "private/lists/draft.md"));
        assertThat(files.getFile(workspace, Optional.empty(), "private/draft.md")
                        .expectedAbsence())
                .isTrue();
        assertThat(files.getFile(workspace, Optional.empty(), "private/lists/draft.md")
                        .source())
                .contains("---\nroute: /article\n---\n# Private alias");
        var publicMove = new RepositoryMoveRequest(relocated.commit(), "public/article.md", "public/moved-article.md");
        assertThatThrownBy(() -> moves.move(agent, workspace, publicMove)).isInstanceOf(AuthException.class);
        var movedPublic = moves.move(owner, workspace, publicMove);
        assertThat(snapshots.current(workspace).commit()).contains(movedPublic.commit());
        assertThat(snapshots.current(workspace).articles())
                .singleElement()
                .extracting(a -> a.route())
                .isEqualTo("/moved-article");

        auth.revokeApiKey(owner, workspace, issued.id());
        assertThatThrownBy(() -> moves.move(
                        agent,
                        workspace,
                        new RepositoryMoveRequest(
                                movedPublic.commit(), "private/lists/draft.md", "private/forbidden.md")))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> patches.apply(
                        agent,
                        workspace,
                        new RepositoryPatch(
                                Optional.of(movedPublic.commit()),
                                List.of(create("private/forbidden.md", "# Denied")))))
                .isInstanceOf(AuthException.class);
        assertThat(files.getFile(workspace, Optional.empty(), "private/forbidden.md")
                        .expectedAbsence())
                .isTrue();
    }

    private static RepositoryTextChange create(String path, String content) {
        return new RepositoryTextChange(path, true, Optional.empty(), Optional.of(content));
    }
}
