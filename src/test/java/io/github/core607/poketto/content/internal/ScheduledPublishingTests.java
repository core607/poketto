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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduledPublishingTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final MovingClock clock = new MovingClock(Instant.parse("2026-09-24T01:00:00Z"));

    @Test
    void aScheduledArticleAppearsWhenDueWithoutANewCommit() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), clock, Duration.ofHours(1));
        fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        text("enabled: true\nmode: public-root\nexclude: []\n"),
                        "public/now.md",
                        text("---\ncreated_at: 2026-09-20\n---\n# Now"),
                        "public/later.md",
                        text("---\npublish_at: 2026-09-24T09:30:00+08:00\n---\n# Later"),
                        "public/named.md",
                        text("---\ncreated_at: 2026-09-01\npublish_at: 2026-09-24T01:30:00Z\n---\n# Named"),
                        "public/typo.md",
                        text("---\npublish_at: next monday\n---\n# Typo"),
                        "private/draft.md",
                        text("---\npublish_at: 2026-09-24T00:00:00Z\n---\n# Private stays private"),
                        "private/someday.md",
                        text("---\npublish_at: 2026-12-01\n---\n# Never published from here")));

        PublicContentSnapshot before = snapshots.refresh(workspace);
        assertThat(before.articles()).extracting(PublicArticle::route).containsExactly("/now");
        assertThat(before.scheduled())
                .containsOnlyKeys("public/later.md", "public/named.md")
                .containsEntry("public/later.md", Instant.parse("2026-09-24T01:30:00Z"));
        // Readers comparing views between two calls see one object until publication changes.
        clock.now = clock.now.plusSeconds(600);
        assertThat(snapshots.current(workspace)).isSameAs(before);
        PublicContentSnapshot viewed = snapshots.withCurrent(workspace, snapshot -> snapshot);
        assertThat(viewed).isSameAs(before);

        clock.now = Instant.parse("2026-09-24T01:30:00Z");
        PublicContentSnapshot due = snapshots.current(workspace);
        assertThat(due.scheduled()).isEmpty();
        assertThat(due.articles()).extracting(PublicArticle::route).containsExactly("/later", "/now", "/named");
        PublicArticle later = due.articles().getFirst();
        assertThat(later.createdAt()).isEqualTo(Instant.parse("2026-09-24T01:30:00Z"));
        assertThat(later.updatedAt()).isEqualTo(later.createdAt());
        assertThat(due.articles().getLast().createdAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));

        // A renewal of the same commit keeps the due articles.
        clock.now = clock.now.plusSeconds(60);
        assertThat(snapshots.refresh(workspace).articles()).hasSize(3);
        var tree = new JGitRepositoryContentReader(fixture.authority()).readTree(workspace, Optional.empty());
        assertThat(tree.diagnostics())
                .filteredOn(diagnostic -> diagnostic.path().equals("public/typo.md"))
                .extracting(RepositoryDiagnostic::code)
                .containsExactly("INVALID_MARKDOWN");
        // On a private file the key schedules nothing, so its dates stay the commit's.
        var someday = tree.documents().stream()
                .filter(document -> document.file().path().equals("private/someday.md"))
                .findFirst()
                .orElseThrow();
        assertThat(someday.publishAt()).isNull();
        assertThat(someday.createdAt()).isBefore(Instant.parse("2026-12-01T00:00:00Z"));
    }

    private static byte[] text(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
