package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetainedBaselineBindingTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COMMIT = "1".repeat(40);
    private final RetainedCopyRecord.Owner owner = new RetainedCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID());
    private final UUID copy = UUID.randomUUID();
    private final long expiry = 1_800_000_000_000L;

    @Test
    void referenceSurvivesSerializationAndRejectsEveryMismatchedIdentityDimension() {
        var reference = RetainedBaselineTestData.reference(owner, copy, COMMIT);
        var record = record(reference);
        assertThat(JSON.readValue(JSON.writeValueAsBytes(record), RetainedCopyRecord.class)
                        .originalBaseline())
                .isEqualTo(reference);
        var anotherSubject = new RetainedCopyRecord.Owner(UUID.randomUUID(), owner.workspaceId());
        var anotherWorkspace = new RetainedCopyRecord.Owner(owner.subjectId(), UUID.randomUUID());
        for (var wrong : List.of(
                RetainedBaselineTestData.reference(anotherSubject, copy, COMMIT),
                RetainedBaselineTestData.reference(anotherWorkspace, copy, COMMIT),
                RetainedBaselineTestData.reference(owner, UUID.randomUUID(), COMMIT),
                RetainedBaselineTestData.reference(owner, copy, "2".repeat(40)))) {

            assertThatThrownBy(() -> record(wrong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("original baseline identity");
        }
        assertThatThrownBy(() -> record(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("original baseline");
    }

    @Test
    void publicScopeCannotCarryAPrivateBaselineReference() {
        var reference = RetainedBaselineTestData.reference(owner, copy, COMMIT);
        assertThatThrownBy(() -> RetainedBaseline.validateBinding(owner, copy, false, COMMIT, reference))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private data");
    }

    private RetainedCopyRecord record(RetainedBaseline.Reference reference) {
        return new RetainedCopyRecord(
                1,
                owner,
                copy,
                0,
                1,
                "a".repeat(64),
                true,
                null,
                expiry,
                new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                new RetainedCopyRecord.Checkpoint(
                        UUID.randomUUID(), "b".repeat(64), 128, new SelectedFileSaves.State(COMMIT).snapshot()),
                null,
                null,
                reference);
    }
}
