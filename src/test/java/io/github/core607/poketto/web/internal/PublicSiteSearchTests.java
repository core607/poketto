package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import io.github.core607.poketto.workspace.WorkspacePublications.Publication;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicSiteSearchTests {
    private static final Instant CREATED = Instant.parse("2026-09-14T00:00:00Z");
    private static final String COMMIT = "a".repeat(40);

    @Test
    void searchesEveryPublishedSpaceWithStableSpaceIdentityAndOrder() {
        WorkspaceId first = id(1);
        WorkspaceId second = id(2);
        var publications = new FakePublications(
                publication(first, "a-space", "A Space"), publication(second, "b-space", "B Space"));
        var snapshots = new FakeSnapshots(
                snapshot(first, article("/same", "Needle")), snapshot(second, article("/same", "Needle")));

        var page = search(publications, snapshots).search("Needle", 0, 10);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(PublicSiteSearch.Result::space).containsExactly("a-space", "b-space");
        assertThat(page.items()).extracting(result -> result.document().route()).containsExactly("/same", "/same");
        assertThat(page.items())
                .extracting(result -> result.document().authorName())
                .containsExactly("A Space", "B Space");
        assertThat(snapshots.currentCalls).isEqualTo(2);
        assertThat(snapshots.withCurrentCalls).isEqualTo(2);
    }

    @Test
    void walksTheCompleteCatalogueAcrossItsHundredItemPages() {
        var publicationValues = new ArrayList<WorkspacePublications.Publication>();
        var snapshotValues = new ArrayList<PublicContentSnapshot>();
        for (int value = 1; value <= 101; value++) {
            WorkspaceId workspace = id(value);
            publicationValues.add(publication(workspace, "space-" + value, "Space " + value));
            snapshotValues.add(snapshot(workspace, article("/note-" + value, "Needle")));
        }
        var publications = new FakePublications(publicationValues.toArray(Publication[]::new));
        var snapshots = new FakeSnapshots(snapshotValues.toArray(PublicContentSnapshot[]::new));
        var siteSearch = new PublicSiteSearch(
                publications, snapshots, new PublicSiteSearch.Limits(101, 101, 2_000, Duration.ofSeconds(5)), () -> 0);

        assertThat(siteSearch.search("Needle", 0, 100).total()).isEqualTo(101);
        assertThat(publications.catalogueCalls).isEqualTo(4);
        assertThat(snapshots.currentCalls).isEqualTo(101);
    }

    @Test
    void refusesAChangedCatalogueAtTheEndBeforeReturningResults() {
        WorkspaceId workspace = id(1);
        Publication original = publication(workspace, "space", "Space");
        List<Publication[]> changes = List.of(
                new Publication[] {original, publication(id(2), "new-space", "New Space")},
                new Publication[0],
                new Publication[] {publication(workspace, "space", "Changed signature")});
        for (Publication[] changed : changes) {
            var publications = new FakePublications(original);
            publications.changeAtFinalCatalogue(changed);
            var snapshots = new FakeSnapshots(snapshot(workspace, article("/same", "Needle")));

            assertThatThrownBy(() -> search(publications, snapshots).search("Needle", 0, 10))
                    .isInstanceOf(ContentRepositoryException.class)
                    .hasMessageContaining("repeat the query");
            assertThat(publications.catalogueCalls).isEqualTo(2);
        }
    }

    @Test
    void refusesUnavailableSnapshotBeforeReturningAnyPartialResults() {
        WorkspaceId workspace = id(1);
        var publications = new FakePublications(publication(workspace, "space", "Space"));
        var snapshots = new FakeSnapshots(snapshot(workspace, article("/same", "Needle")));
        snapshots.currentFailure = new ContentRepositoryException("snapshot unavailable");

        assertThatThrownBy(() -> search(publications, snapshots).search("Needle", 0, 10))
                .isSameAs(snapshots.currentFailure);
        assertThat(snapshots.currentCalls).isEqualTo(1);
        assertThat(snapshots.withCurrentCalls).isZero();
    }

    @Test
    void refusesSearchWhenTheCorpusChangesBeforeResponse() {
        WorkspaceId workspace = id(1);
        var publications = new FakePublications(publication(workspace, "space", "Space"));
        var snapshots = new FakeSnapshots(snapshot(workspace, article("/same", "Needle")));
        snapshots.changeOnValidation = true;

        assertThatThrownBy(() -> search(publications, snapshots).search("Needle", 0, 10))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("repeat the query");
        assertThat(snapshots.withCurrentCalls).isEqualTo(1);
    }

    @Test
    void refusesSpaceAndDocumentAndSourceBoundsWithoutLargeFixtures() {
        WorkspaceId first = id(1);
        WorkspaceId second = id(2);
        var publications = new FakePublications(
                publication(first, "a-space", "A Space"), publication(second, "b-space", "B Space"));
        var snapshots = new FakeSnapshots(
                snapshot(first, article("/one", "Needle")), snapshot(second, article("/two", "Needle")));

        assertCapacity(new PublicSiteSearch(
                publications, snapshots, new PublicSiteSearch.Limits(1, 10, 1024, Duration.ofSeconds(5)), () -> 0));
        assertCapacity(new PublicSiteSearch(
                publications, snapshots, new PublicSiteSearch.Limits(4, 1, 1024, Duration.ofSeconds(5)), () -> 0));
        assertCapacity(new PublicSiteSearch(
                publications, snapshots, new PublicSiteSearch.Limits(4, 10, 5, Duration.ofSeconds(5)), () -> 0));
    }

    @Test
    void refusesWhenTheDeadlineExpiresDuringCatalogueRead() {
        WorkspaceId workspace = id(1);
        var publications = new FakePublications(publication(workspace, "space", "Space"));
        var snapshots = new FakeSnapshots(snapshot(workspace, article("/same", "Needle")));
        AtomicInteger calls = new AtomicInteger();
        LongSupplier clock =
                () -> calls.getAndIncrement() == 0 ? 0 : Duration.ofSeconds(6).toNanos();

        assertCapacity(new PublicSiteSearch(
                publications, snapshots, new PublicSiteSearch.Limits(4, 10, 1024, Duration.ofSeconds(5)), clock));
        assertThat(snapshots.currentCalls).isZero();
    }

    @Test
    void retainsSharedDocumentSearchBounds() {
        WorkspaceId workspace = id(1);
        var publications = new FakePublications(publication(workspace, "space", "Space"));
        var snapshots = new FakeSnapshots(snapshot(workspace, article("/same", "Needle")));
        var search = search(publications, snapshots);

        assertThatThrownBy(() -> search.search("x".repeat(201), 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search("Needle", 10_001, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search("Needle", 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshots.currentCalls).isZero();
    }

    private static PublicSiteSearch search(FakePublications publications, FakeSnapshots snapshots) {
        return new PublicSiteSearch(
                publications, snapshots, new PublicSiteSearch.Limits(4, 10, 1024, Duration.ofSeconds(5)), () -> 0);
    }

    private static void assertCapacity(PublicSiteSearch search) {
        assertThatThrownBy(() -> search.search("Needle", 0, 10))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static WorkspaceId id(int value) {
        return new WorkspaceId(new UUID(0, value));
    }

    private static WorkspacePublications.Publication publication(WorkspaceId id, String slug, String name) {
        return new WorkspacePublications.Publication(id, slug, name, true, true, "");
    }

    private static PublicContentSnapshot snapshot(WorkspaceId workspace, PublicArticle... articles) {
        return new PublicContentSnapshot(
                workspace, Optional.of(COMMIT), CREATED, CREATED.plusSeconds(3600), List.of(articles));
    }

    private static PublicArticle article(String route, String title) {
        return new PublicArticle(
                route.substring(1) + ".md",
                route,
                title,
                "Needle body",
                List.of("notes"),
                CREATED,
                CREATED,
                false,
                "",
                null,
                false);
    }

    private static final class FakePublications implements WorkspacePublications {
        private final List<Publication> values;
        private List<Publication> finalValues;
        private int catalogueCalls;

        private FakePublications(Publication... values) {
            this.values = sorted(List.of(values));
        }

        private void changeAtFinalCatalogue(Publication... values) {
            finalValues = sorted(List.of(values));
        }

        private static List<Publication> sorted(List<Publication> values) {
            return values.stream()
                    .sorted(Comparator.comparing(
                            publication -> publication.workspaceId().value()))
                    .toList();
        }

        @Override
        public Optional<Publication> findPublished(String slug) {
            return values.stream()
                    .filter(publication -> publication.slug().equals(slug))
                    .findFirst();
        }

        @Override
        public List<Publication> publishedAfter(Optional<WorkspaceId> after, int limit) {
            catalogueCalls++;
            List<Publication> current = catalogueCalls > 1 && finalValues != null ? finalValues : values;
            return current.stream()
                    .filter(publication -> after.isEmpty()
                            || publication
                                            .workspaceId()
                                            .value()
                                            .compareTo(after.orElseThrow().value())
                                    > 0)
                    .limit(limit)
                    .toList();
        }

        @Override
        public Publication settings(WorkspaceId workspace) {
            return values.stream()
                    .filter(publication -> publication.workspaceId().equals(workspace))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public Publication setEnabled(WorkspaceId workspace, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Publication setAuthorName(WorkspaceId workspace, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Publication setDisplayName(WorkspaceId workspace, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Publication setDescription(WorkspaceId workspace, String description) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Publication setPublicHistory(WorkspaceId workspace, boolean shown) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void requireEnabled(WorkspaceId workspace) {
            if (settings(workspace).enabled() == false) {
                throw new IllegalStateException("disabled");
            }
        }
    }

    private static final class FakeSnapshots implements PublicContentSnapshots {
        private final Map<WorkspaceId, PublicContentSnapshot> values = new HashMap<>();
        private int currentCalls;
        private int withCurrentCalls;
        private boolean changeOnValidation;
        private RuntimeException currentFailure;

        private FakeSnapshots(PublicContentSnapshot... snapshots) {
            for (PublicContentSnapshot snapshot : snapshots) {
                values.put(snapshot.workspaceId(), snapshot);
            }
        }

        @Override
        public void ensureReady(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspaceId) {
            currentCalls++;
            if (currentFailure != null) {
                throw currentFailure;
            }
            return values.get(workspaceId);
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspaceId, Function<PublicContentSnapshot, T> action) {
            withCurrentCalls++;
            PublicContentSnapshot current = values.get(workspaceId);
            if (changeOnValidation) {
                current = new PublicContentSnapshot(
                        workspaceId,
                        Optional.of("b".repeat(40)),
                        current.verifiedAt(),
                        current.expiresAt(),
                        current.articles());
            }
            return action.apply(current);
        }
    }
}
