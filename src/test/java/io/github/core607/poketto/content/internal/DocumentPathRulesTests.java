package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentPathRulesTests {

    @Test
    void collisionKeyUsesUnicodeNormalizationAndCaseFolding() {
        assertThat(DocumentPathRules.collisionKey("documents/Café.md"))
                .isEqualTo(DocumentPathRules.collisionKey("documents/CAFE\u0301.MD"));
        assertThat(DocumentPathRules.collisionKey("documents/Straße.md"))
                .isEqualTo(DocumentPathRules.collisionKey("documents/STRASSE.md"));
    }
}
