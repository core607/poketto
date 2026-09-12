package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bridge reply is the agent's view of a {@code poketto} command, so its JSON shape is behavior.
 * These tests pin the three rules that are easy to break silently: an unset part is omitted, an
 * absent listing cursor is kept as null, and no reply at all is an empty object.
 */
class BridgeReplyShapeTests {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void anUnsetPartIsOmittedRatherThanSentAsNull() {
        assertThat(JSON.writeValueAsString(BridgeReplies.failed("ACCESS_DENIED")))
                .isEqualTo("{\"ok\":false,\"code\":\"ACCESS_DENIED\"}");
        assertThat(JSON.writeValueAsString(BridgeReplies.failed("INDEX_MISSING", "Restore it.")))
                .isEqualTo("{\"ok\":false,\"code\":\"INDEX_MISSING\",\"message\":\"Restore it.\"}");
        assertThat(JSON.writeValueAsString(BridgeReplies.removed())).isEqualTo("{\"ok\":true,\"removed\":true}");
        assertThat(JSON.writeValueAsString(BridgeReplies.failedBecause("MEDIA_UNAVAILABLE", "CAPACITY")))
                .isEqualTo("{\"ok\":false,\"code\":\"MEDIA_UNAVAILABLE\",\"reason\":\"CAPACITY\"}");
    }

    @Test
    void noReplyYetIsAnEmptyObject() {
        assertThat(JSON.writeValueAsString(new BridgeReplies.Absent())).isEqualTo("{}");
    }

    /**
     * The listing cursor and the pinned commit sit in one object with opposite rules, so a single
     * inclusion policy for the record would break one of them.
     */
    @Test
    void aListingKeepsItsCursorAndDropsAnUnpinnedCommit() {
        var page = new BridgeReplies.MediaPage(
                "worktree",
                "a".repeat(64),
                null,
                List.of(new BridgeReplies.MediaItem("a.png", "image/png", 3)),
                1,
                0,
                null);
        String rendered = JSON.writeValueAsString(page);
        assertThat(rendered).contains("\"nextOffset\":null").doesNotContain("\"commit\"");
        assertThat(JSON.writeValueAsString(
                        new BridgeReplies.MediaPage("repository", "a".repeat(64), "c".repeat(40), List.of(), 0, 0, 2)))
                .contains("\"commit\":\"" + "c".repeat(40) + "\"")
                .contains("\"nextOffset\":2");
    }

    /** A move that is not yet acknowledged reports a null commit; dropping it would read as no move. */
    @Test
    void aPendingMoveKeepsItsUnknownCommit() {
        var pending = new BridgeReplies.MovePending(false, null, false, "a", "b");
        assertThat(JSON.writeValueAsString(pending)).contains("\"commit\":null");
    }
}
