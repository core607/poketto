package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentRepositoryTemplateTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void repositoryTemplateStartsClosedAndEnablingItPublishesOnlySelectedContent() throws Exception {
        var files = new java.util.LinkedHashMap<String, byte[]>();
        Path template = Path.of("content-template");
        try (var paths = java.nio.file.Files.walk(template)) {
            for (Path file : paths.filter(java.nio.file.Files::isRegularFile).toList()) {
                files.put(
                        template.relativize(file).toString().replace('\\', '/'),
                        java.nio.file.Files.readAllBytes(file));
            }
        }
        assertThat(files)
                .containsKeys("AGENTS.md", "private/AGENTS.md", "public/AGENTS.md", RepositoryPublishingPolicy.PATH);
        files.put("public/selected.md", text("# Selected"));
        files.put("private/new.md", text("# New private content"));
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, files);
        var snapshots = new JGitPublicContentSnapshots(
                fixture.authority(), java.time.Clock.systemUTC(), java.time.Duration.ofHours(1));
        assertThat(snapshots.refresh(workspace).articles()).isEmpty();
        files.put(RepositoryPublishingPolicy.PATH, text("enabled: true\nmode: public-root\n"));
        fixture.commitRemote(workspace, files);
        assertThat(snapshots.refresh(workspace).articles())
                .extracting(article -> article.route())
                .containsExactly("/selected");
    }

    private static byte[] text(String source) {
        return source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
