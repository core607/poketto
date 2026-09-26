package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentValueTests {

    @Test
    void acceptsOnlyCanonicalDocumentIds() {
        String canonical = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(DocumentId.parse(canonical).toString()).isEqualTo(canonical);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DocumentId.parse(canonical.toUpperCase()))
                .withMessageContaining("canonical lowercase UUID");
    }

    @Test
    void revisionPinsTheExactBlobBytes() {
        DocumentRevision hello = DocumentRevision.sha256("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(hello.toString())
                .isEqualTo("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(DocumentRevision.sha256("line\n".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(DocumentRevision.sha256("line\r\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(DocumentRevision.sha256("line\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(DocumentRevision.sha256("line\n".getBytes(StandardCharsets.UTF_8)));
    }
}
