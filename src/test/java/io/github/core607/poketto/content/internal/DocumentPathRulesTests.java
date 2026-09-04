package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.core607.poketto.content.ContentLimits;
import org.junit.jupiter.api.Test;

class DocumentPathRulesTests {

    @Test
    void acceptsNestedMarkdownBelowTheManagedDirectory() {
        assertThat(DocumentPathRules.validate("documents/notes/entry.md")).isEqualTo("documents/notes/entry.md");
    }

    @Test
    void boundsThePathLength() {
        String longest = "documents/" + "n".repeat(ContentLimits.MAX_PATH_LENGTH - "documents/.md".length()) + ".md";

        assertThat(DocumentPathRules.validate(longest)).hasSize(ContentLimits.MAX_PATH_LENGTH);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DocumentPathRules.validate(longest.replace(".md", "n.md")))
                .withMessageContaining("must not exceed " + ContentLimits.MAX_PATH_LENGTH + " characters");
    }

    @Test
    void rejectsPathsThatAreAbsoluteTraversingOrNotMarkdown() {
        for (String candidate : new String[] {
            "/documents/note.md", "documents/../note.md", "documents\\note.md", "other/note.md", "documents/note.MD"
        }) {
            assertThatIllegalArgumentException().as(candidate).isThrownBy(() -> DocumentPathRules.validate(candidate));
        }
    }

    @Test
    void rejectsNamesWindowsCannotStore() {
        for (String candidate : new String[] {
            "documents/a:b.md",
            "documents/what?.md",
            "documents/a\tb.md",
            "documents/con.md",
            "documents/COM1.md",
            "documents/trailing./note.md",
            "documents/trailing /note.md",
            "documents/.md"
        }) {
            assertThatIllegalArgumentException().as(candidate).isThrownBy(() -> DocumentPathRules.validate(candidate));
        }
    }

    @Test
    void collisionKeyUsesUnicodeNormalizationAndCaseFolding() {
        assertThat(DocumentPathRules.collisionKey("documents/Café.md"))
                .isEqualTo(DocumentPathRules.collisionKey("documents/CAFE\u0301.MD"));
        assertThat(DocumentPathRules.collisionKey("documents/Straße.md"))
                .isEqualTo(DocumentPathRules.collisionKey("documents/STRASSE.md"));
    }
}
