package io.github.core607.poketto.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CorrectionsProposalTests {
    private static final String BASE = "sha256:" + "0".repeat(64);

    @Test
    void loneSurrogatesInBodyOrReasonAreRefusedLikeOtherCommunityText() {
        assertThatThrownBy(() -> new Corrections.Proposal("/essay", BASE, "broken \uD800 body", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correction body contains invalid Unicode");
        assertThatThrownBy(() -> new Corrections.Proposal("/essay", BASE, "body", "stray \uDC00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correction reason contains invalid Unicode");
        assertThatThrownBy(() -> new Corrections.Proposal("/essay", BASE, "body", "ends high \uD83D", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correction reason contains invalid Unicode");
    }

    @Test
    void surrogatePairsRemainValid() {
        var proposal = new Corrections.Proposal("/essay", BASE, "cat 😸", " thanks 😸 ", null);
        assertThat(proposal.body()).isEqualTo("cat 😸");
        assertThat(proposal.reason()).isEqualTo("thanks 😸");
        assertThat(proposal.credited()).isTrue();
    }
}
