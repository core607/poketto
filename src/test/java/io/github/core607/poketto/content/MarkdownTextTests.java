package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTextTests {
    @Test
    void extractsRenderedLabelsBeforeTruncationAndPreservesCodeLiterals() {
        String markdown = "# 雨天\n\n[城市 **漫步**](https://example.org/%E9%9B%A8) &amp; `![code](literal)`"
                + "\n\n![窗边](photo.png)\n\n<!-- hidden sentinel -->\n\n最后一行";
        assertThat(MarkdownText.summary("雨天", markdown)).isEqualTo("城市 漫步 & ![code](literal) 窗边 最后一行");
        assertThat(MarkdownText.visible("前**中**后\\*尾")).isEqualTo("前中后*尾");
    }

    @Test
    void suppressesOnlyTheFirstMatchingLevelOneHeading() {
        assertThat(MarkdownText.summary("雨天", "# **雨天**\n\n内容\n\n# 雨天")).isEqualTo("内容 雨天");
        assertThat(MarkdownText.summary("雨天", "## 雨天\n\n内容")).isEqualTo("雨天 内容");
        assertThat(MarkdownText.summary("雨天", "引言\n\n# 雨天")).isEqualTo("引言 雨天");
        assertThat(MarkdownText.summary("另一标题", "雨天\n====\n\n内容")).isEqualTo("雨天 内容");
        assertThat(MarkdownText.summary("雨天", "雨天\n====\n\n内容")).isEqualTo("内容");
    }

    @Test
    void readsGfmContentWithoutTableDelimitersTasksOrStrikeMarkup() {
        String markdown = "| 地点 | 天气 |\n| --- | --- |\n| 公园 | ~~下雨~~晴天 |\n\n"
                + "- [x] 出门\n- [ ] 回家\n\n正文[^note]\n\n[^note]: 补充内容";
        assertThat(MarkdownText.visible(markdown))
                .contains("地点 天气 公园 下雨晴天", "出门", "回家", "正文", "补充内容")
                .doesNotContain("---", "|", "~~", "[x]", "[ ]", "[^", "note");
    }

    @Test
    void includesOnlyReachableFootnotesInReadingOrderAndTerminatesCycles() {
        String body = "开始[^A]，结束[^missing]\n\n[^unused]: 隐藏哨兵[^also-unused]\n"
                + "[^also-unused]: 隐藏二\n[^B]: 第二条[^a]\n[^a]: 第一条[^B]";
        assertThat(MarkdownText.visible(body)).isEqualTo("开始，结束[^missing] 第一条 第二条");
        assertThat(MarkdownText.visible("[^unused]: 隐藏正文")).isEmpty();
    }

    @Test
    void ignoresRawHtmlButKeepsVisibleInlineTextAndCodeBlocks() {
        assertThat(MarkdownText.visible(
                        "<script>hidden</script>\n\n前<span>中</span>后\n\n" + "```md\n# literal\n[x](y)\n```\n\n尾"))
                .isEqualTo("前中后 # literal [x](y) 尾");
    }

    @Test
    void searchMatchesVisibleLabelsButNotHiddenDestinations() {
        String body = "# 旅行\n\n[公园散步](https://example.org/secret-destination)";
        var visible = new DocumentSearch("公园散步", "", null, null, 0, 10);
        var hidden = new DocumentSearch("secret-destination", "", null, null, 0, 10);
        assertThat(visible.matches("旅行", body, List.of(), Instant.EPOCH)).isTrue();
        assertThat(hidden.matches("旅行", body, List.of(), Instant.EPOCH)).isFalse();
        assertThat(visible.snippet("旅行", body)).isEqualTo("公园散步");
    }

    @Test
    void aLongLiteralMatchSurvivesTheBoundedExcerpt() {
        String query = "中".repeat(200);
        var search = new DocumentSearch(query, "", null, null, 0, 10);
        String snippet = search.snippet("标题", "前".repeat(500) + query + "后".repeat(300));
        assertThat(snippet).contains(query).hasSize(DocumentSearch.SNIPPET_LENGTH);
        var emoji = new DocumentSearch("目标", "", null, null, 0, 10);
        String result = emoji.snippet("标题", "😸".repeat(200) + "a目标" + "😸".repeat(200));
        assertThat(result).contains("目标");
        assertThat(result.length()).isLessThanOrEqualTo(DocumentSearch.SNIPPET_LENGTH);
        assertThat(Character.isLowSurrogate(result.charAt(0))).isFalse();
        assertThat(Character.isHighSurrogate(result.charAt(result.length() - 1)))
                .isFalse();
    }
}
