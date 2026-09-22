package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArticleIdentitySnapshotTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final UUID id = UUID.randomUUID();

    @Test
    void followsExternalMovesAndRouteChangesWithoutRewritingSource() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var snapshots = snapshots(fixture);
        for (String path : new String[] {"public/old.md", "public/folder/moved.md"}) {
            String source = "---\nid: " + id + "\n---\n# Original";
            fixture.commitRemote(workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(), path, text(source)));
            PublicArticle article = snapshots.refresh(workspace).articles().getFirst();
            assertThat(article.articleId()).isEqualTo(id);
            assertThat(article.route()).isEqualTo(RepositoryPathRules.route(path));
            assertThat(reader.getFile(workspace, Optional.empty(), path).source())
                    .contains(source);
        }
        fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        policy(),
                        "public/moved.md",
                        text("---\nid: " + id + "\nroute: /chosen-address\n---\n# Renamed")));
        PublicArticle renamed = snapshots.refresh(workspace).articles().getFirst();
        assertThat(renamed.articleId()).isEqualTo(id);
        assertThat(renamed.route()).isEqualTo("/chosen-address");
    }

    @Test
    void duplicateIdsDisableBothIdentitiesUntilResolvedWhilePrivateCopiesDoNot() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var snapshots = snapshots(fixture);
        byte[] source = text("---\nid: " + id + "\n---\n# Article");
        fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        policy(),
                        "public/one.md",
                        source,
                        "public/two.md",
                        source,
                        "public/legacy.md",
                        text("---\nid: old-slug\n---\n# Legacy")));
        PublicContentSnapshot duplicated = snapshots.refresh(workspace);
        assertThat(duplicated.articles()).hasSize(3).allMatch(article -> article.articleId() == null);
        assertThat(reader.readTree(workspace, Optional.empty()).diagnostics())
                .extracting(RepositoryDiagnostic::code)
                .contains("DUPLICATE_ARTICLE_ID", "INVALID_ARTICLE_ID");
        fixture.commitRemote(
                workspace,
                Map.of(RepositoryPublishingPolicy.PATH, policy(), "public/one.md", source, "private/copy.md", source));
        assertThat(snapshots.refresh(workspace).articles())
                .singleElement()
                .extracting(PublicArticle::articleId)
                .isEqualTo(id);
        assertThat(duplicated.articles()).allMatch(article -> article.articleId() == null);
    }

    private JGitPublicContentSnapshots snapshots(RemoteRepositoryFixture fixture) {
        return new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
    }

    private static byte[] policy() {
        return text("enabled: true\nmode: public-root\nexclude: []\n");
    }

    private static byte[] text(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
