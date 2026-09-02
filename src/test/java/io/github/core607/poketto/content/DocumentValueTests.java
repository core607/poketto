package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentValueTests {

    @Test
    void acceptsOnlyCanonicalDocumentIdsAndVisibilityValues() {
        String canonical = "550e8400-e29b-41d4-a716-446655440000";

        assertThat(DocumentId.parse(canonical).toString()).isEqualTo(canonical);
        assertThat(DocumentVisibility.parse("private")).isEqualTo(DocumentVisibility.PRIVATE);
        assertThat(DocumentVisibility.parse("public")).isEqualTo(DocumentVisibility.PUBLIC);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DocumentId.parse(canonical.toUpperCase()))
                .withMessageContaining("canonical lowercase UUID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DocumentVisibility.parse("PUBLIC"))
                .withMessageContaining("exactly private or public");
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

    @Test
    void metadataTrimsDisplayValuesAndRejectsNormalizedTagDuplicates() {
        DocumentMetadata metadata = metadata("  Title  ", List.of("  Example ", "中文"));

        assertThat(metadata.title()).isEqualTo("Title");
        assertThat(metadata.tags()).containsExactly("Example", "中文");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metadata("Title", List.of("CAFÉ", "cafe\u0301")))
                .withMessageContaining("Unicode normalization and case folding");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metadata("Title\nInjected", List.of()))
                .withMessageContaining("control characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentMetadata(
                        DocumentId.parse("550e8400-e29b-41d4-a716-446655440000"),
                        "Title",
                        DocumentVisibility.PRIVATE,
                        List.of(),
                        Instant.parse("2026-08-27T00:00:00Z"),
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Optional.empty()))
                .withMessageContaining("must not precede creation");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DocumentMetadata(
                        DocumentId.parse("550e8400-e29b-41d4-a716-446655440000"),
                        "Title",
                        DocumentVisibility.PUBLIC,
                        List.of(),
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-27T00:00:00Z"),
                        Optional.of(Instant.parse("2026-08-28T00:00:00Z"))))
                .withMessageContaining("must not follow the last update");
    }

    private static DocumentMetadata metadata(String title, List<String> tags) {
        return new DocumentMetadata(
                DocumentId.parse("550e8400-e29b-41d4-a716-446655440000"),
                title,
                DocumentVisibility.PRIVATE,
                tags,
                Instant.parse("2026-08-26T09:00:00Z"),
                Instant.parse("2026-08-26T09:00:00Z"),
                Optional.empty());
    }
}
