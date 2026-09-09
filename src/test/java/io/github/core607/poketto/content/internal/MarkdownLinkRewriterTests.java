package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownLinkRewriterTests {
    @Test
    void preservesContainerPrefixesAroundMultilineLinksAndDefinitions() {
        String source = "> [a](\r\n> old.md \"title\")\r\n>\r\n> [ref]:\r\n>   old.md\r\n>\r\n> [ref]\r\n";
        assertThat(MarkdownLinkRewriter.rewrite(source, s -> s.equals("old.md") ? "folder/new.md" : s))
                .isEqualTo(source.replace("old.md", "folder/new.md"));
    }

    @Test
    void preservesExactSourceOutsideParsedDestinations() {
        String source = "# Heading\r\n\r\n[x](old.md \"title\") ![a](<old.md>)\r\n"
                + "`[example](old.md)`\r\n```md\r\n[x](old.md)\r\n```\r\n"
                + "<img src=\"old.md\">\r\n[reference][a] [a]\r\n\r\n[a]: old.md 'title'\r\n";
        String result = MarkdownLinkRewriter.rewrite(source, s -> s.equals("old.md") ? "new/note.md" : s);
        assertThat(result)
                .isEqualTo(source.replace("[x](old.md \"title\")", "[x](new/note.md \"title\")")
                        .replace("![a](<old.md>)", "![a](new/note.md)")
                        .replace("[a]: old.md", "[a]: new/note.md"));
    }

    @Test
    void rewritesNestedImagesEscapedDestinationsAndDefinitionsWithoutTouchingLabels() {
        String source = "[![cover](img.png)](note.md) [weird](a\\(b\\).md) [entity](a&amp;b.md)\n"
                + "[ref][]\n\n[ref]:\n  <a%20b.md> \"title\"\n";
        assertThat(MarkdownLinkRewriter.rewrite(source, s -> switch (s) {
                    case "img.png" -> "images/img.png";
                    case "note.md" -> "notes/note.md";
                    case "a(b).md" -> "a%28b%29.md";
                    case "a&b.md" -> "a%26b.md";
                    case "a%20b.md" -> "notes/a%20b.md";
                    default -> s;
                }))
                .isEqualTo("[![cover](images/img.png)](notes/note.md) [weird](a%28b%29.md) [entity](a%26b.md)\n"
                        + "[ref][]\n\n[ref]:\n  notes/a%20b.md \"title\"\n");
    }
}
