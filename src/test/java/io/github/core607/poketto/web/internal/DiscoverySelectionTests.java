package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.content.PublicArticle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DiscoverySelectionTests {
    @Test
    void authoredRecentAndDiverseChoicesLeaveRoomForOlderArticles() {
        var articles = new ArrayList<PublicArticle>();
        articles.add(article("featured", 1, true, "java"));
        articles.add(article("recent", 30, false, "java"));
        articles.add(article("diverse", 2, false, "music", "art"));
        for (int day = 3; day < 20; day++) {
            articles.add(article("older-" + day, day, false, "java"));
        }
        var explored = new HashSet<String>();
        var random = new Random(23);
        for (int attempt = 0; attempt < 100; attempt++) {
            List<PublicArticle> result = DiscoverySelection.select(articles, "", random);
            assertThat(result).hasSize(4).doesNotHaveDuplicates();
            assertThat(result).extracting(PublicArticle::route).contains("/featured", "/recent", "/diverse");
            explored.add(result.getLast().route());
        }
        assertThat(explored).hasSize(17);
    }

    @Test
    void exactTagFiltersEverySlotAndFeaturedFloodCannotIncreaseTheSpaceCap() {
        var articles = new ArrayList<PublicArticle>();
        for (int day = 1; day < 30; day++) {
            articles.add(article("featured-" + day, day, true, "java"));
        }
        articles.add(article("one", 30, false, "猫"));
        articles.add(article("two", 31, true, "猫"));
        assertThat(DiscoverySelection.select(articles, "", new Random(4)))
                .hasSize(4)
                .doesNotHaveDuplicates();
        assertThat(DiscoverySelection.select(articles, "猫", new Random(4)))
                .extracting(PublicArticle::route)
                .containsExactlyInAnyOrder("/one", "/two");
        assertThat(DiscoverySelection.select(articles, "Java", new Random(4))).isEmpty();
        assertThat(DiscoverySelection.select(List.of(), "", new Random(4))).isEmpty();
    }

    private static PublicArticle article(String name, int day, boolean featured, String... tags) {
        Instant date = Instant.EPOCH.plusSeconds(day * 86400L);
        return new PublicArticle(
                "public/" + name + ".md",
                "/" + name,
                name,
                "Body",
                List.of(tags),
                date,
                date,
                false,
                "",
                null,
                featured);
    }
}
