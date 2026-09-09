package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Synthetic Git authority and real projection service for the native worker acceptance. */
public final class PublicExecutionNativeFixture {
    private final RemoteRepositoryFixture repository;
    private final WorkspaceId workspace;
    private final JGitPublicContentSnapshots snapshots;
    private final RepositorySnapshotExports exports;
    private final String sourceCommit;

    public PublicExecutionNativeFixture(Path root, Path staging, AuthService auth, WorkspaceId workspace)
            throws Exception {
        this.workspace = workspace;
        repository = new RemoteRepositoryFixture(root);
        repository.commitRemote(workspace, Map.of("private/secret.md", text("historic-secret-needle")));
        sourceCommit = repository
                .commitRemote(
                        workspace,
                        Map.of(
                                RepositoryPublishingPolicy.PATH,
                                text("enabled: true\nmode: public-by-default\n"),
                                "article.md",
                                text("---\ntitle: Public native article\nsecret: metadata-secret-needle\n---\n"
                                        + "public-native-body\n\n<!-- comment-secret-needle -->\n"),
                                "private/secret.md",
                                text("current-secret-needle"),
                                "AGENTS.md",
                                text("operator-secret-needle")))
                .name();
        snapshots = new JGitPublicContentSnapshots(repository.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        exports = new JGitRepositorySnapshotExports(
                repository.authority(), auth, staging, 1024 * 1024, Duration.ofSeconds(10), snapshots);
    }

    public RepositorySnapshotExports exports() {
        return exports;
    }

    public String sourceCommit() {
        return sourceCommit;
    }

    public void withdraw() throws Exception {
        repository.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, text("enabled: false\nmode: public-by-default\n")));
        snapshots.refresh(workspace);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
