package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositoryMediaIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MediaListingTests {
    private final ObjectMapper json = new ObjectMapper();
    private final RepositoryMediaIndex.Media media =
            new RepositoryMediaIndex.Media(UUID.randomUUID(), "a".repeat(64), "image/png", 10);

    @Test
    void byteLimitedPagesRemainCompleteAndExposeOnlyLogicalMetadata() {
        var entries = new LinkedHashMap<String, RepositoryMediaIndex.Media>();
        for (int i = 29; i >= 0; i--) {
            entries.put("public/" + String.format("%02d", i) + "猫\"".repeat(120) + ".png", media);
        }
        var files = new RepositoryMediaIndex(entries).files();
        var returned = new ArrayList<String>();
        int offset = 0;
        do {
            var query = new MediaListing.Query("public/", offset, 200, "b".repeat(64), null);
            var page = MediaListing.page(files, query, "b".repeat(64), "public-projection");
            assertThat(json.writeValueAsBytes(page).length).isLessThanOrEqualTo(MediaListing.MAX_PAGE_BYTES);
            var result = json.valueToTree(page).path("result");
            assertThat(result.path("items").size()).isPositive().isLessThan(30);
            assertThat(result.path("total").intValue()).isEqualTo(30);
            result.path("items").forEach(item -> {
                assertThat(item.propertyNames()).containsExactlyInAnyOrder("path", "mediaType", "size");
                returned.add(item.path("path").stringValue());
            });
            offset = result.path("nextOffset").isNull()
                    ? -1
                    : result.path("nextOffset").intValue();
        } while (offset >= 0);
        assertThat(returned).containsExactlyElementsOf(files.keySet());
        assertThat(returned).doesNotHaveDuplicates();
    }

    @Test
    void changedVersionReturnsNoStalePageAndOffsetsPastTheEndAreEmpty() {
        var files = new RepositoryMediaIndex(Map.of("music/one.png", media, "musicology/two.png", media)).files();
        var changed = MediaListing.page(
                files, new MediaListing.Query("music/", 0, 100, "a".repeat(64), null), "b".repeat(64), "worktree");
        assertThat(changed.ok()).isEqualTo(false);
        assertThat(changed.code()).isEqualTo("MEDIA_INDEX_CHANGED");
        assertThat(changed.result()).isNull();
        var page = json.valueToTree(MediaListing.page(
                        files, new MediaListing.Query("music/", 1, 100, null, null), "b".repeat(64), "worktree"))
                .path("result");
        assertThat(page.path("items").isEmpty()).isTrue();
        assertThat(page.path("total").intValue()).isEqualTo(1);
        assertThat(page.path("nextOffset").isNull()).isTrue();
        assertThat(page.has("commit")).isFalse();
    }

    @Test
    void bridgeArgumentsRejectCoercionUnknownFieldsAndUnboundedPagination() {
        var valid = json.readTree("{\"prefix\":\"\",\"offset\":0,\"limit\":100,\"indexVersion\":null,\"commit\":null}");
        assertThat(MediaListing.Query.parse(valid).limit()).isEqualTo(100);
        for (String field : new String[] {"offset", "limit"}) {
            var quoted = valid.deepCopy();
            ((ObjectNode) quoted).put(field, "100");
            assertThatThrownBy(() -> MediaListing.Query.parse(quoted)).isInstanceOf(IllegalArgumentException.class);
        }
        var excess = valid.deepCopy();
        ((ObjectNode) excess).put("limit", 201);
        assertThatThrownBy(() -> MediaListing.Query.parse(excess)).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode) excess).put("limit", 100).put("hostPath", "/tmp");
        assertThatThrownBy(() -> MediaListing.Query.parse(excess)).isInstanceOf(IllegalArgumentException.class);
    }
}
