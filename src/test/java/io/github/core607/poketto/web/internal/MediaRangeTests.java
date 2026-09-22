package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MediaRangeTests {
    @Test
    void selectsExactClosedOpenAndSuffixRanges() {
        assertThat(MediaRange.parse("bytes=3-5", 10)).isEqualTo(new MediaRange(3, 3, true));
        assertThat(MediaRange.parse("bytes=8-", 10)).isEqualTo(new MediaRange(8, 2, true));
        assertThat(MediaRange.parse("bytes=8-999", 10)).isEqualTo(new MediaRange(8, 2, true));
        assertThat(MediaRange.parse("bytes=-3", 10)).isEqualTo(new MediaRange(7, 3, true));
        assertThat(MediaRange.parse("bytes=-999", 10)).isEqualTo(new MediaRange(0, 10, true));
        assertThat(MediaRange.parse("bytes=0-0", 1).contentRange(1)).isEqualTo("bytes 0-0/1");
        assertThat(MediaRange.parse(null, 10)).isEqualTo(new MediaRange(0, 10, false));
        assertThat(MediaRange.parse("items=1-2", 10)).isEqualTo(new MediaRange(0, 10, false));
        assertThat(MediaRange.parse("bytes=0-1,3-4", 10)).isEqualTo(new MediaRange(0, 10, false));
    }

    @Test
    void rejectsInvalidUnsatisfiableAndOverflowingRanges() {
        for (String range : new String[] {
            "bytes=-0", "bytes=-", "bytes=10-", "bytes=8-2", "bytes=0--1", "bytes=999999999999999999999999-"
        }) {
            assertThatThrownBy(() -> MediaRange.parse(range, 10)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> MediaRange.parse("bytes=0-0", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
