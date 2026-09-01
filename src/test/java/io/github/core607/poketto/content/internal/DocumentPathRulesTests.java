package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DocumentPathRulesTests {

    @Test
    void acceptsNestedMarkdownBelowTheManagedDirectory() {
        assertThat(DocumentPathRules.validate("documents/notes/entry.md")).isEqualTo("documents/notes/entry.md");
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
