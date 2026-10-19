package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.PublicAlbumCover;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DiscoveryBatchSizeTests {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");

    @Test
    void smallSpaceExhaustionLetsAnotherSpaceCompleteFourFullPages() {
        var fixture = new Fixture(List.of(5, 213));
        List<PublicDiscovery.Page> pages = fixture.pages(null, null);
        assertThat(pages).hasSize(4).allSatisfy(page -> assertThat(page.items()).hasSize(6));
        List<PublicDiscovery.Card> cards = cards(pages);
        assertThat(cards).hasSize(24).doesNotHaveDuplicates();
        assertThat(cards.stream().filter(card -> card.space().equals("space-0")))
                .hasSize(5);
        assertThat(cards.stream().filter(card -> card.space().equals("space-1")))
                .hasSize(19);
        for (PublicDiscovery.Page page : pages) {
            assertThat(fixture.discovery.page(page.batch(), null, page.offset(), null))
                    .isEqualTo(page);
        }
    }

    @Test
    void equallyPopulatedSpacesReceiveEqualSupplementalSlots() {
        var fixture = new Fixture(List.of(50, 50));
        List<PublicDiscovery.Card> cards = cards(fixture.pages(null, null));
        assertThat(cards).hasSize(24).doesNotHaveDuplicates();
        assertThat(cards.stream().collect(Collectors.groupingBy(PublicDiscovery.Card::space, Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(Map.of("space-0", 12L, "space-1", 12L));
    }

    @Test
    void tagsAndExhaustionApplyToSupplementalCards() {
        var fixture = new Fixture(List.of(30, 30));
        List<PublicDiscovery.Card> cards = cards(fixture.pages(null, "tag-1"));
        assertThat(cards).hasSize(24).doesNotHaveDuplicates().allSatisfy(card -> {
            assertThat(card.space()).isEqualTo("space-1");
            assertThat(card.tags()).contains("tag-1");
        });
        assertThat(cards(fixture.pages(null, "absent"))).isEmpty();
        assertThat(cards(new Fixture(List.of(3, 2)).pages(null, null)))
                .hasSize(5)
                .doesNotHaveDuplicates();
    }

    @Test
    void fullCatalogKeepsTheInitialSpaceQuotaAndThe128CardBound() {
        var fixture = new Fixture(IntStream.range(0, 33).mapToObj(ignored -> 10).toList());
        List<PublicDiscovery.Page> pages = fixture.pages(null, null);
        List<PublicDiscovery.Card> cards = cards(pages);
        assertThat(cards).hasSize(128).doesNotHaveDuplicates();
        assertThat(cards.stream().collect(Collectors.groupingBy(PublicDiscovery.Card::space, Collectors.counting())))
                .hasSize(32)
                .allSatisfy((space, count) -> assertThat(count).isEqualTo(4));
        assertThat(cards(fixture.pages(pages.getFirst().batch(), null)))
                .hasSize(10)
                .allSatisfy(card -> assertThat(card.space()).isEqualTo("space-32"));
    }

    @Test
    void withdrawalDoesNotRefillOrMoveAnExistingBatch() {
        var fixture = new Fixture(List.of(30, 30));
        List<PublicDiscovery.Page> pages = fixture.pages(null, null);
        when(fixture.publications.findPublished("space-0")).thenReturn(Optional.empty());
        for (PublicDiscovery.Page before : pages) {
            var after = fixture.discovery.page(before.batch(), null, before.offset(), null);
            assertThat(after.items())
                    .containsExactlyElementsOf(before.items().stream()
                            .filter(card -> !card.space().equals("space-0"))
                            .toList());
            assertThat(after.nextOffset()).isEqualTo(before.nextOffset());
            assertThat(after.previousOffset()).isEqualTo(before.previousOffset());
        }
    }

    private static List<PublicDiscovery.Card> cards(List<PublicDiscovery.Page> pages) {
        return pages.stream().flatMap(page -> page.items().stream()).toList();
    }

    private static final class Fixture {
        private final WorkspacePublications publications = mock(WorkspacePublications.class);
        private final PublicDiscovery discovery;

        @SuppressWarnings("unchecked")
        private Fixture(List<Integer> sizes) {
            var spaces = new ArrayList<WorkspacePublications.Publication>();
            var current = new HashMap<WorkspaceId, PublicContentSnapshot>();
            for (int index = 0; index < sizes.size(); index++) {
                var workspace = WorkspaceId.random();
                String slug = "space-" + index;
                var space = new WorkspacePublications.Publication(workspace, slug, slug, true, true, "", "", false);
                spaces.add(space);
                int number = index;
                List<PublicArticle> articles = IntStream.range(0, sizes.get(index))
                        .mapToObj(article -> article(number, article))
                        .toList();
                current.put(
                        workspace,
                        new PublicContentSnapshot(
                                workspace, Optional.of("a".repeat(40)), NOW, NOW.plusSeconds(3600), articles));
                when(publications.findPublished(slug)).thenReturn(Optional.of(space));
            }
            when(publications.publishedAfter(any(), anyInt())).thenAnswer(call -> {
                Optional<WorkspaceId> after = call.getArgument(0);
                int start = after.map(id -> IntStream.range(0, spaces.size())
                                        .filter(index ->
                                                spaces.get(index).workspaceId().equals(id))
                                        .findFirst()
                                        .orElseThrow()
                                + 1)
                        .orElse(0);
                int limit = call.getArgument(1);
                return spaces.stream().skip(start).limit(limit).toList();
            });
            var snapshots = mock(PublicContentSnapshots.class);
            when(snapshots.withCurrent(any(), any()))
                    .thenAnswer(call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1))
                            .apply(current.get(call.getArgument(0))));
            var assets = mock(AssetService.class);
            when(assets.publicCovers(any(), any())).thenAnswer(call -> {
                List<PublicArticle> requested = call.getArgument(1);
                assertThat(requested).hasSizeLessThanOrEqualTo(6);
                return requested.stream()
                        .collect(Collectors.toMap(PublicArticle::route, ignored -> new PublicAlbumCover(false, null)));
            });
            discovery = new PublicDiscovery(publications, snapshots, assets, Clock.fixed(NOW, ZoneOffset.UTC), null);
        }

        private List<PublicDiscovery.Page> pages(String afterBatch, String tag) {
            var pages = new ArrayList<PublicDiscovery.Page>();
            PublicDiscovery.Page page = discovery.page(null, afterBatch, 0, tag);
            pages.add(page);
            while (page.nextOffset() != null) {
                page = discovery.page(page.batch(), null, page.nextOffset(), null);
                pages.add(page);
            }
            return pages;
        }
    }

    private static PublicArticle article(int space, int index) {
        String name = "article-" + index;
        Instant date = NOW.minusSeconds(index);
        return new PublicArticle(
                "public/" + name + ".md",
                "/" + name,
                name,
                "Body",
                List.of("fixture", "tag-" + space),
                date,
                date,
                false,
                "",
                null,
                index == 1);
    }
}
