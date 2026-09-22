package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryMarkdownParserTests {
    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "'true'", "[true]", "null", "0"})
    void onlyAnAuthoredBooleanEnablesTheOptionalFeaturedSignal(String value) {
        var result = new RepositoryMarkdownParser()
                .parse("public/note.md", "---\nfeatured: " + value + "\n---\n# Note\nBody");
        assertThat(result.featured()).isEqualTo(value.equals("true"));
        assertThat(result.title()).isEqualTo("Note");
        assertThat(result.body()).isEqualTo("# Note\nBody");
    }
}
