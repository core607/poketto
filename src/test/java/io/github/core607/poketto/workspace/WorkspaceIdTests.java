package io.github.core607.poketto.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceIdTests {

    @Test
    void acceptsOnlyCanonicalLowercaseUuidText() {
        String canonical = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(WorkspaceId.parse(canonical).toString()).isEqualTo(canonical);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkspaceId.parse(canonical.toUpperCase()))
                .withMessageContaining("canonical lowercase UUID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkspaceId.parse("../" + canonical))
                .withMessageContaining("canonical lowercase UUID");
    }

    @Test
    void uuidValueAlwaysRendersCanonically() {
        UUID value = UUID.randomUUID();

        assertThat(new WorkspaceId(value).toString()).isEqualTo(value.toString());
    }
}
