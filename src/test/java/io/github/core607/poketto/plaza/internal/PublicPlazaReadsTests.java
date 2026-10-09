package io.github.core607.poketto.plaza.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentSearch;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.plaza.PlazaException;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublicPlazaReadsTests {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private final WorkspacePublications publications = mock(WorkspacePublications.class);
    private final Snapshots snapshots = new Snapshots();
    private final List<WorkspacePublications.Publication> catalogue = new ArrayList<>();

    @Test
    void searchesAcrossSpacesWithoutLeakingSourcePathsOrOriginalCommits() {
        add("one", "/same", "Needle from one");
        add("two", "/same", "Needle from two");
        var reads = reads();
        var result = reads.search("Needle", "", 0);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(PublicPlazaReads.Card::reference).containsExactly("one/same", "two/same");
        assertThat(result.items())
                .extracting(PublicPlazaReads.Card::url)
                .containsExactly("/s/one/read/same", "/s/two/read/same");
        String serialized = new ObjectMapper().writeValueAsString(result);
        assertThat(serialized).doesNotContain("source-only-name", "a".repeat(40), "repositoryPath");
    }

    @Test
    void refusalNeverReturnsPartialMatchesWhenAnotherSnapshotIsUnavailable() {
        var own = add("one", "/a", "Needle");
        var broken = add("two", "/b", "Needle");
        snapshots.values.remove(broken.workspaceId());
        assertThatThrownBy(() -> reads().search("Needle", "", 0)).isInstanceOf(ContentRepositoryException.class);
        assertThat(reads().mirror(own.workspaceId(), 0).items())
                .extracting(PublicPlazaReads.Card::reference)
                .containsExactly("one/a");
    }

    @Test
    void snapshotReplacementAndWebsiteWithdrawalInvalidatePreparedReads() {
        var publication = add("one", "/a", "Needle");
        var reads = reads();
        snapshots.changeAtValidation = true;
        assertThatThrownBy(() -> reads.search("Needle", "", 0)).isInstanceOf(ContentRepositoryException.class);
        snapshots.changeAtValidation = false;
        doThrow(new PublicationUnavailableException()).when(publications).requireEnabled(publication.workspaceId());
        assertThatThrownBy(() -> reads.read("one/a", 0)).isInstanceOf(PublicationUnavailableException.class);
    }

    @Test
    void completeScanChecksCapacityAndMissingArticleReadsRemainGeneric() {
        add("one", "/a", "Needle");
        reads();
        var limited = new PublicPlazaReads(
                publications, snapshots, new PublicPlazaReads.Limits(10, 10, 1, Duration.ofSeconds(5)), () -> 0L);
        assertThatThrownBy(() -> limited.search("Needle", "", 0))
                .isInstanceOf(PlazaException.class)
                .extracting(failure -> ((PlazaException) failure).code())
                .isEqualTo("CAPACITY");
        assertThatThrownBy(() -> reads().read("one/private-secret", 0))
                .isInstanceOf(PlazaException.class)
                .hasMessage("That pocket is not open to the street.");
    }

    @Test
    void readPaginationPreservesUnicodeAndPublicLinkEscaping() {
        String text = "a".repeat(8191) + "😸tail";
        add("one", "/space #?%", text);
        reads();
        var reads = new PublicPlazaReads(publications, snapshots, "https://plaza.example.test/");
        var first = reads.read("one/space #?%", 0);
        var second = reads.read("one/space #?%", first.nextOffset());
        assertThat(first.text() + second.text()).isEqualTo(text);
        assertThat(first.article().url()).isEqualTo("https://plaza.example.test/s/one/read/space%20%23%3F%25");
    }

    @Test
    void metadataIsInsideTheCorpusBudgetAndConfiguredOriginCannotEmbedSecrets() {
        add("one", "/a", "");
        reads();
        var limited = new PublicPlazaReads(
                publications, snapshots, new PublicPlazaReads.Limits(10, 10, 1, Duration.ofSeconds(5)), () -> 0L);
        assertThatThrownBy(() -> limited.search("", "", 0)).isInstanceOf(PlazaException.class);
        for (String invalid : List.of("https://user:secret@example.test", "https://example.test/sub", "file:///tmp")) {
            assertThatThrownBy(() -> new PublicPlazaReads(publications, snapshots, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private PublicPlazaReads reads() {
        when(publications.publishedAfter(any(), anyInt())).thenReturn(List.copyOf(catalogue));
        return new PublicPlazaReads(publications, snapshots);
    }

    @Test
    void largeResultSetsNeverSuggestAnUncallableNextPage() {
        WorkspacePublications.Publication publication = add("one", "/a", "needle");
        List<PublicArticle> articles = IntStream.range(0, 10011)
                .mapToObj(index -> new PublicArticle(
                        "public/" + index,
                        "/" + index,
                        "Paper",
                        "needle",
                        List.of(),
                        NOW,
                        NOW,
                        false,
                        "Author",
                        null,
                        false))
                .toList();
        var snapshot = new PublicContentSnapshot(
                publication.workspaceId(), Optional.of("a".repeat(40)), NOW, NOW.plusSeconds(600), articles);
        PublicPlazaReads.Page page = reads().search(
                        List.of(new PublicPlazaReads.Source(publication, snapshot)),
                        new DocumentSearch("needle", "", null, null, 10000, 10));
        assertThat(page.total()).isEqualTo(10011);
        assertThat(page.items()).hasSize(10);
        assertThat(page.nextOffset()).isNull();
        assertThat(page.refineQuery()).isTrue();
    }

    private WorkspacePublications.Publication add(String slug, String route, String body) {
        var id = WorkspaceId.random();
        var publication = new WorkspacePublications.Publication(id, slug, slug, true, true, "author", "", false);
        catalogue.add(publication);
        when(publications.findPublished(slug)).thenReturn(Optional.of(publication));
        when(publications.settings(id)).thenReturn(publication);
        var article = new PublicArticle(
                "public/source-only-name.md",
                route,
                "A public paper",
                body,
                List.of("topic"),
                NOW,
                NOW,
                false,
                "author",
                null,
                false,
                null);
        snapshots.values.put(
                id,
                new PublicContentSnapshot(
                        id, Optional.of("a".repeat(40)), NOW, NOW.plusSeconds(600), List.of(article)));
        return publication;
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private final Map<WorkspaceId, PublicContentSnapshot> values = new HashMap<>();
        private boolean changeAtValidation;

        @Override
        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("Public actions must not fetch");
        }

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("Public actions must not fetch");
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspace) {
            var value = values.get(workspace);
            if (value == null) {
                throw new ContentRepositoryException("Unavailable fixture");
            }
            return value;
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> action) {
            var value = current(workspace);
            if (changeAtValidation) {
                value = new PublicContentSnapshot(
                        workspace, Optional.of("b".repeat(40)), NOW, NOW.plusSeconds(600), value.articles());
            }
            return action.apply(value);
        }
    }
}
