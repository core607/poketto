package io.github.core607.poketto.plaza.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordRankingTests {

    @Test
    void splitsOnUnicodeSpacesAndCountsFoldedRepeatsOnce() {
        assertThat(KeywordRanking.parse(" 　\t  ").isEmpty()).isTrue();
        assertThat(KeywordRanking.parse("a b c d e f g h A h").isEmpty()).isFalse();
        assertThatThrownBy(() -> KeywordRanking.parse("a b c d e f g h i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use at most 8 keywords");

        KeywordRanking.Scan<String> scan = KeywordRanking.parse("植物园　雨伞").scan();
        scan.add("both", "雾港植物园", "雨天可借用雨伞。");
        scan.add("one", "雾港植物园", "雨天闭园。");
        assertThat(scan.matches()).extracting(KeywordRanking.Scored::item).containsExactly("both");
    }

    @Test
    void foldingIgnoresLetterCaseWithoutMovingOffsets() {
        assertThat(KeywordRanking.fold("ABC😸ſ")).isEqualTo("abc😸s").hasSize("ABC😸ſ".length());
        assertThat(KeywordRanking.fold("雨伞")).isSameAs("雨伞");

        KeywordRanking.Scan<String> scan = KeywordRanking.parse("rain SPAIN").scan();
        scan.add("upper", "Weather", "THE RAIN IN SPAIN");
        scan.add("lower", "Weather", "the rain in spain");
        scan.add("missing", "Weather", "the rain in france");
        assertThat(scan.matches()).extracting(KeywordRanking.Scored::item).containsExactly("upper", "lower");
    }

    @Test
    void documentsWithoutEveryKeywordStillLowerThatKeywordsWeight() {
        KeywordRanking.Scan<String> scan = KeywordRanking.parse("moss fern").scan();
        scan.add("more-moss", "Paper", "moss moss moss fern");
        scan.add("more-fern", "Paper", "moss fern fern fern");
        for (int index = 0; index < 4; index++) {
            scan.add("moss-only-" + index, "Paper", "moss beside stones");
        }
        List<KeywordRanking.Scored<String>> matches = scan.matches();
        assertThat(matches).extracting(KeywordRanking.Scored::item).containsExactly("more-moss", "more-fern");
        assertThat(matches.get(1).score()).isGreaterThan(matches.get(0).score());
    }

    @Test
    void aTitleOccurrenceOutweighsABodyOccurrence() {
        KeywordRanking.Scan<String> scan = KeywordRanking.parse("lantern").scan();
        scan.add("body", "Notes", "a lantern by the door");
        scan.add("title", "Lantern", "a light by the door");
        scan.add("none", "Notes", "a light by the door");
        List<KeywordRanking.Scored<String>> matches = scan.matches();
        assertThat(matches).extracting(KeywordRanking.Scored::item).containsExactly("body", "title");
        assertThat(matches.get(1).score()).isGreaterThan(matches.get(0).score());
    }

    @Test
    void snippetCutsAroundTheEarliestKeywordInItsOriginalCase() {
        String markdown = "x".repeat(500) + " The NEEDLE waits. " + "y".repeat(500) + " haystack";
        String snippet = KeywordRanking.parse("haystack needle").snippet("Paper", markdown);
        assertThat(snippet).contains("The NEEDLE waits.").hasSize(240);
        assertThat(snippet.indexOf("NEEDLE")).isEqualTo(60);
        assertThat(KeywordRanking.parse("paper").snippet("Paper", "Opening words."))
                .isEqualTo("Opening words.");
    }
}
