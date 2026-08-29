package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentMetadata;
import io.github.core607.poketto.content.DocumentVisibility;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CanonicalDocumentCodecTests {

    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private final CanonicalDocumentCodec codec = new CanonicalDocumentCodec();

    @Test
    void serializesInCanonicalFieldOrderWithUtf8LfAndOneFinalNewline() {
        DocumentContent document = new DocumentContent(
                new DocumentMetadata(
                        DocumentId.parse(ID),
                        "Example \"document\"",
                        DocumentVisibility.PUBLIC,
                        List.of("Example", "中文"),
                        Instant.parse("2026-08-26T09:00:00Z"),
                        Instant.parse("2026-08-27T10:30:00Z"),
                        Optional.of(Instant.parse("2026-08-27T09:00:00Z"))),
                "Line one\r\n中文\r\n\r\n");

        String canonical = new String(codec.serialize(document), StandardCharsets.UTF_8);

        assertThat(canonical).isEqualTo("""
                ---
                id: 550e8400-e29b-41d4-a716-446655440000
                title: "Example \\"document\\""
                visibility: public
                tags:
                  - "Example"
                  - "中文"
                created_at: 2026-08-26T09:00:00Z
                updated_at: 2026-08-27T10:30:00Z
                published_at: 2026-08-27T09:00:00Z
                ---

                Line one
                中文
                """);
        assertThat(canonical).doesNotContain("\r");
        DocumentContent parsed = codec.parse(codec.serialize(document));
        assertThat(parsed.body()).isEqualTo("Line one\n中文");
        assertThat(parsed.metadata().publishedAt())
                .contains(Instant.parse("2026-08-27T09:00:00Z"));
    }

    @Test
    void emptyBodyAndTagsRoundTripWithoutPublishedAt() {
        DocumentContent document = document("Title", List.of(), "");

        byte[] serialized = codec.serialize(document);

        assertThat(new String(serialized, StandardCharsets.UTF_8)).endsWith("---\n\n");
        assertThat(codec.parse(serialized)).isEqualTo(document);
    }

    @Test
    void parsesUtcOffsetsAndUnicodeBodies() {
        byte[] source = validSource()
                .replace("2026-08-26T09:00:00Z", "2026-08-26T09:00:00+00:00")
                .replace("Markdown body.", "正文 🌸")
                .getBytes(StandardCharsets.UTF_8);

        DocumentContent parsed = codec.parse(source);

        assertThat(parsed.metadata().createdAt()).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        assertThat(parsed.body()).isEqualTo("正文 🌸");
    }

    @Test
    void rejectsUnsupportedYamlFeaturesAndSchemaViolations() {
        assertInvalid(validSource().replace("title: Example", "title: Example\ntitle: Again"),
                "Duplicate");
        assertInvalid(validSource().replace("tags:\n  - example", "tags: &items [example]"),
                "aliases or anchors");
        assertInvalid(validSource().replace("title: Example", "title: !private Example"),
                "custom YAML tags");
        assertInvalid(validSource().replace("title: Example", "title: Example\n...\nextra: value"),
                "exactly one YAML document");
        assertInvalid(validSource().replace("title: Example", "title: Example\nsummary: unknown"),
                "unknown fields");
        assertInvalid(validSource().replace("tags:\n  - example", "tags: example"),
                "YAML sequence");
        assertInvalid(validSource().replace("created_at: 2026-08-26T09:00:00Z",
                        "created_at: 2026-08-26T10:00:00+01:00"),
                "UTC offset");
    }

    @Test
    void rejectsMalformedEncodingAndDelimiters() {
        byte[] bom = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '-', '-', '-'};

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parse(bom))
                .withMessageContaining("byte-order mark");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parse(new byte[] {(byte) 0xc3, (byte) 0x28}))
                .withMessageContaining("valid UTF-8");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parse("id: missing delimiter\n".getBytes(StandardCharsets.UTF_8)))
                .withMessageContaining("must begin");
    }

    @Test
    void updatePreservesIdentityAndPublicationWhileAdvancingOnlyRealChanges() {
        DocumentContent current = document("Title", List.of("one"), "Body");
        DocumentContent unchangedWithInventedTimestamp = new DocumentContent(
                new DocumentMetadata(
                        current.metadata().id(),
                        current.metadata().title(),
                        current.metadata().visibility(),
                        current.metadata().tags(),
                        current.metadata().createdAt(),
                        Instant.parse("2030-01-01T00:00:00Z"),
                        current.metadata().publishedAt()),
                current.body());

        assertThat(codec.update(
                        current,
                        unchangedWithInventedTimestamp,
                        Instant.parse("2026-08-27T00:00:00Z")))
                .isSameAs(current);

        DocumentContent changed = new DocumentContent(
                new DocumentMetadata(
                        current.metadata().id(),
                        "Changed",
                        DocumentVisibility.PUBLIC,
                        List.of("one"),
                        current.metadata().createdAt(),
                        Instant.parse("2026-08-27T00:00:00Z"),
                        Optional.of(Instant.parse("2026-08-27T00:00:00Z"))),
                current.body());
        DocumentContent updated = codec.update(
                current, changed, Instant.parse("2026-08-27T00:00:01Z"));

        assertThat(updated.metadata().createdAt()).isEqualTo(current.metadata().createdAt());
        assertThat(updated.metadata().updatedAt()).isEqualTo(Instant.parse("2026-08-27T00:00:01Z"));
        assertThat(updated.metadata().publishedAt())
                .contains(Instant.parse("2026-08-27T00:00:00Z"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.update(
                        updated,
                        new DocumentContent(
                                new DocumentMetadata(
                                        updated.metadata().id(),
                                        updated.metadata().title(),
                                        DocumentVisibility.PRIVATE,
                                        updated.metadata().tags(),
                                        updated.metadata().createdAt(),
                                        updated.metadata().updatedAt(),
                                        Optional.empty()),
                                updated.body()),
                        Instant.parse("2026-08-28T00:00:00Z")))
                .withMessageContaining("published_at cannot be changed or erased");
    }

    private void assertInvalid(String source, String message) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parse(source.getBytes(StandardCharsets.UTF_8)))
                .withMessageContaining(message);
    }

    private static DocumentContent document(String title, List<String> tags, String body) {
        return new DocumentContent(
                new DocumentMetadata(
                        DocumentId.parse(ID),
                        title,
                        DocumentVisibility.PRIVATE,
                        tags,
                        Instant.parse("2026-08-26T09:00:00Z"),
                        Instant.parse("2026-08-26T09:00:00Z"),
                        Optional.empty()),
                body);
    }

    private static String validSource() {
        return """
                ---
                id: 550e8400-e29b-41d4-a716-446655440000
                title: Example
                visibility: private
                tags:
                  - example
                created_at: 2026-08-26T09:00:00Z
                updated_at: 2026-08-26T09:00:00Z
                ---

                Markdown body.
                """;
    }
}
