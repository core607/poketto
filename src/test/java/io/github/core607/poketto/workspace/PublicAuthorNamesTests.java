package io.github.core607.poketto.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PublicAuthorNamesTests {
    @Test
    void trimsOuterWhitespaceAndAcceptsBlankFallback() {
        assertThat(PublicAuthorNames.normalize("\u2028  Article  \u2029")).isEqualTo("Article");
        assertThat(PublicAuthorNames.normalize(" \n\t ")).isEmpty();
    }

    @Test
    void rejectsUnicodeLineSeparatorsInsideTheSignature() {
        for (String separator : new String[] {"\u2028", "\u2029"}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PublicAuthorNames.normalize("Before" + separator + "After"))
                    .withMessageContaining("single-line");
        }
    }
}
