package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.PublicRevisionHistory;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PublicHistoryTests {
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");
    private final WorkspaceId workspace = WorkspaceId.random();
    private final WorkspacePublications publications = mock(WorkspacePublications.class);
    private final PublicContentSnapshots snapshots = mock(PublicContentSnapshots.class);
    private final PublicRevisionHistory history = mock(PublicRevisionHistory.class);
    private final PublicArticle essay =
            new PublicArticle("public/essay.md", "/essay", "Essay", "new", List.of(), NOW, NOW, false, "", null, false);

    @Test
    void aSpaceWithoutTheSettingAnswersNotFoundWithoutReadingGit() {
        publish(false);
        assertThatThrownBy(() -> service().read("home", "/essay")).isInstanceOf(PublicResourceNotFoundException.class);
        verifyNoInteractions(snapshots, history);
    }

    @Test
    void versionsAreOldestFirstAndCarryNoCommitMetadata() throws Exception {
        publish(true);
        serve(snapshot("a"), snapshot("a"));
        when(history.read(workspace, "a".repeat(40), "public/essay.md", "/essay"))
                .thenReturn(new PublicRevisionHistory.Revisions(
                        List.of(
                                new PublicRevisionHistory.Revision(NOW, "new"),
                                new PublicRevisionHistory.Revision(NOW.minusSeconds(60), "old")),
                        true));

        var read = service().read("home", "/essay");
        assertThat(read.versions()).extracting(PublicHistory.Version::body).containsExactly("old", "new");
        assertThat(JsonMapper.builder().build().writeValueAsString(read))
                .isEqualTo("{\"route\":\"/essay\",\"title\":\"Essay\",\"versions\":["
                        + "{\"savedAt\":\"2026-09-24T00:59:00Z\",\"body\":\"old\"},"
                        + "{\"savedAt\":\"2026-09-24T01:00:00Z\",\"body\":\"new\"}],\"complete\":true}");
    }

    @Test
    void aSnapshotThatMovedDuringTheWalkDiscardsTheAnswer() {
        publish(true);
        serve(snapshot("a"), snapshot("b"));
        when(history.read(any(), any(), any(), any())).thenReturn(new PublicRevisionHistory.Revisions(List.of(), true));
        assertThatThrownBy(() -> service().read("home", "/essay")).isInstanceOf(ContentRepositoryException.class);
    }

    private PublicHistory service() {
        return new PublicHistory(publications, snapshots, history);
    }

    private void publish(boolean history) {
        when(publications.findPublished("home"))
                .thenReturn(Optional.of(
                        new WorkspacePublications.Publication(workspace, "home", "Home", true, true, "", "", history)));
    }

    @SuppressWarnings("unchecked")
    private void serve(PublicContentSnapshot first, PublicContentSnapshot after) {
        when(snapshots.current(workspace)).thenReturn(first);
        when(snapshots.withCurrent(any(), any()))
                .thenAnswer(
                        invocation -> ((Function<PublicContentSnapshot, ?>) invocation.getArgument(1)).apply(after));
    }

    private PublicContentSnapshot snapshot(String commit) {
        return new PublicContentSnapshot(
                workspace, Optional.of(commit.repeat(40)), NOW, NOW.plusSeconds(3600), List.of(essay));
    }
}
