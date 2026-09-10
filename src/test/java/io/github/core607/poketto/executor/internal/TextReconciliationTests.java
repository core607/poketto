package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TextReconciliationTests {
    @Test
    void independentEditsPreserveCrLfAndMissingFinalNewline() {
        var result = TextReconciliation.merge(
                Optional.of("one\r\ntwo\r\nthree"),
                Optional.of("LOCAL\r\ntwo\r\nthree"),
                Optional.of("one\r\ntwo\r\nREMOTE"));
        assertThat(result.conflicted()).isFalse();
        assertThat(result.content()).contains("LOCAL\r\ntwo\r\nREMOTE");
    }

    @Test
    void conflictingEditsExposeAllThreeVersions() {
        var result = TextReconciliation.merge(Optional.of("before\n"), Optional.of("local\n"), Optional.of("remote\n"));
        assertThat(result.conflicted()).isTrue();
        assertThat(result.content().orElseThrow())
                .contains("<<<<<<< LOCAL", "||||||| BASE", "before", "local", "remote", ">>>>>>> REMOTE");
    }

    @Test
    void deletionAndEmptyFilesRemainDifferent() {
        var unchangedDeletion =
                TextReconciliation.merge(Optional.of("before"), Optional.empty(), Optional.of("before"));
        assertThat(unchangedDeletion.content()).isEmpty();
        assertThat(unchangedDeletion.conflicted()).isFalse();
        var deletedAgainstEdit = TextReconciliation.merge(Optional.of(""), Optional.empty(), Optional.of("new"));
        assertThat(deletedAgainstEdit.conflicted()).isTrue();
        assertThat(deletedAgainstEdit.content().orElseThrow()).contains("<<<<<<< LOCAL", "new");
        var emptyCreation = TextReconciliation.merge(Optional.empty(), Optional.of(""), Optional.empty());
        assertThat(emptyCreation.content()).contains("");
        assertThat(emptyCreation.conflicted()).isFalse();
    }

    @Test
    void oversizedInputIsRejectedBeforeMergeOrFastPath() {
        var huge = Optional.of("x".repeat(1024 * 1024 + 1));
        assertThatThrownBy(() -> TextReconciliation.merge(huge, huge, huge))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
