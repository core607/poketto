package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryBaselineCacheTests {
    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void evictsTheLeastRecentlyReadSnapshotAndSeparatesWorkspaceAndCommit() {
        var cache = new RepositoryBaselineCache(2, 10_000);
        remember(cache, "a", "one");
        remember(cache, "b", "two");
        assertThat(cache.find(WorkspaceId.random(), "a")).isEmpty();
        assertThat(cache.find(workspace, "a")).isPresent();
        remember(cache, "c", "three");
        assertThat(cache.find(workspace, "a")).isPresent();
        assertThat(cache.find(workspace, "b")).isEmpty();
        assertThat(cache.find(workspace, "c").orElseThrow())
                .extracting(file -> file.source().orElseThrow())
                .containsExactly("three");
    }

    @Test
    void textBudgetEvictsBeforeTheSnapshotCountLimit() {
        var cache = new RepositoryBaselineCache(10, 2048);
        remember(cache, "a", "x".repeat(500));
        remember(cache, "b", "y".repeat(500));
        assertThat(cache.find(workspace, "a")).isEmpty();
        assertThat(cache.find(workspace, "b")).isPresent();
    }

    @Test
    void anOversizedCaptureCannotPublishItsPrefixAndReleasesItsBudget() {
        var cache = new RepositoryBaselineCache(10, 2048);
        try (var capture = cache.capture()) {
            capture.accept(file("a", "x".repeat(500)));
            capture.accept(file("a", "y".repeat(500)));
            cache.remember(workspace, "a", capture);
        }
        assertThat(cache.find(workspace, "a")).isEmpty();
        remember(cache, "b", "z".repeat(500));
        assertThat(cache.find(workspace, "b")).isPresent();
    }

    @Test
    void inProgressCapturesShareTheBudgetAndAbandonmentReleasesIt() {
        var cache = new RepositoryBaselineCache(10, 2048);
        try (var first = cache.capture();
                var second = cache.capture()) {
            first.accept(file("a", "x".repeat(500)));
            second.accept(file("b", "y".repeat(500)));
            cache.remember(workspace, "b", second);
            assertThat(cache.find(workspace, "b")).isEmpty();
        }
        assertThat(cache.find(workspace, "a")).isEmpty();
        remember(cache, "c", "z".repeat(500));
        assertThat(cache.find(workspace, "c")).isPresent();
    }

    @Test
    void replacingASnapshotReleasesItsPreviousReservation() {
        var cache = new RepositoryBaselineCache(10, 2048);
        remember(cache, "a", "one");
        remember(cache, "a", "one");
        remember(cache, "b", "two");
        assertThat(cache.find(workspace, "a")).isPresent();
        assertThat(cache.find(workspace, "b")).isPresent();
    }

    private void remember(RepositoryBaselineCache cache, String commit, String source) {
        try (var capture = cache.capture()) {
            capture.accept(file(commit, source));
            cache.remember(workspace, commit, capture);
        }
    }

    private RepositoryFile file(String commit, String source) {
        return new RepositoryFile(
                workspace,
                Optional.of(commit),
                "note.md",
                false,
                Optional.of(source),
                Optional.of(DocumentRevision.sha256(source.getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
    }
}
