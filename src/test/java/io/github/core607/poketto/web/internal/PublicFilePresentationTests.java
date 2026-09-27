package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicCollections;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PublicFilePresentationTests {
    private final WorkspaceId workspace = WorkspaceId.random();
    private final Instant now = Instant.parse("2026-09-24T01:00:00Z");
    private final Instant release = Instant.parse("2026-09-24T03:00:00Z");

    @Test
    void aScheduledFileReportsItsReleaseInsteadOfBeingUnavailable() {
        List<PublicArticle> due = List.of(new PublicArticle(
                "public/now.md", "/now", "Now", "# Now", List.of(), now, now, false, "", null, false));
        var view = new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                now,
                now.plusSeconds(3600),
                due,
                new PublicCollections(due),
                Map.of("public/later.md", release));
        var publications = mock(WorkspacePublications.class);
        when(publications.settings(workspace))
                .thenReturn(
                        new WorkspacePublications.Publication(workspace, "home", "Home", true, true, "", "", false));
        var presentation = new PublicFilePresentation(publications, new FixedSnapshots(view));

        var scheduled = presentation.page(file("public/later.md"));
        assertThat(scheduled.state()).isEqualTo(PublicFilePresentation.State.SCHEDULED);
        assertThat(scheduled.publishAt()).isEqualTo(release);
        assertThat(scheduled.route()).isNull();

        var available = presentation.page(file("public/now.md"));
        assertThat(available.state()).isEqualTo(PublicFilePresentation.State.AVAILABLE);
        assertThat(available.route()).isEqualTo("/now");
        assertThat(available.publishAt()).isNull();

        assertThat(presentation.page(file("public/missing.md")).state())
                .isEqualTo(PublicFilePresentation.State.UNAVAILABLE);
    }

    private RepositoryFile file(String path) {
        return new RepositoryFile(
                workspace,
                Optional.of("a".repeat(40)),
                path,
                false,
                Optional.of("# x"),
                Optional.empty(),
                List.of(),
                true);
    }

    private record FixedSnapshots(PublicContentSnapshot snapshot) implements PublicContentSnapshots {
        @Override
        public void ensureReady(WorkspaceId workspaceId) {}

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspaceId) {
            return snapshot;
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspaceId) {
            return snapshot;
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspaceId, Function<PublicContentSnapshot, T> action) {
            return action.apply(snapshot);
        }
    }
}
