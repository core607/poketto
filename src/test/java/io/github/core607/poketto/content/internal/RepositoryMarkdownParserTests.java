package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryMarkdownParserTests {
    @Test
    void explicitTitleTakesPrecedenceOverBodyHeadingsWithoutChangingTheBody() {
        String body = "# " + "x".repeat(201) + "\n" + "ordinary body text\n".repeat(20_000);
        var parser = new RepositoryMarkdownParser();
        var result = parser.parse("note.md", "---\ntitle: Authored title\n---\n" + body);
        assertThat(result.title()).isEqualTo("Authored title");
        assertThat(result.body()).isEqualTo(body);
        assertThatThrownBy(() -> parser.parse("note.md", "---\ntitle: '  '\n---\n# Body title\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title must be nonempty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void lazyHeadingFallbackKeepsFenceAndLineEndingSemantics(String newline) {
        var parser = new RepositoryMarkdownParser();
        String source = String.join(newline, "```md", "# Hidden", "````", "Real title", "===", "Body");
        assertThat(parser.parse("note.md", source).title()).isEqualTo("Real title");
        String early = "  ## First title ###" + newline + "ordinary body text\n".repeat(20_000);
        var result = parser.parse("note.md", early);
        assertThat(result.title()).isEqualTo("First title");
        assertThat(result.body()).isEqualTo(early);
    }

    @Test
    void authoredGameLabelNeverGrantsExecutableCapability() {
        var result = new RepositoryMarkdownParser()
                .parse("public/note.md", "---\ntags: [小游戏, puzzle]\n---\n# A game claim\nOrdinary article");
        assertThat(result.tags()).containsExactly("puzzle");
        assertThat(result.body()).contains("Ordinary article");
    }

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
