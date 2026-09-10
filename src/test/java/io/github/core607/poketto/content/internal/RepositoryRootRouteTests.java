package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepositoryRootRouteTests {
    @Test
    void publicRootIsOmittedExactlyOnceWithoutDecodingNames() {
        assertThat(RepositoryPathRules.route("public/index.md")).isEqualTo("/");
        assertThat(RepositoryPathRules.route("public/notes/index.md")).isEqualTo("/notes");
        assertThat(RepositoryPathRules.route("public/notes/page.md")).isEqualTo("/notes/page");
        assertThat(RepositoryPathRules.route("public/public/page.md")).isEqualTo("/public/page");
        assertThat(RepositoryPathRules.route("public/中文 %2F# ?.md")).isEqualTo("/中文 %2F# ?");
        assertThat(RepositoryPathRules.route("private/page.md")).isEqualTo("/private/page");
        assertThat(RepositoryPathRules.route("PUBLIC/page.md")).isEqualTo("/PUBLIC/page");
    }

    @Test
    void explicitRouteIsPreservedButCannotPublishPrivateContent() {
        var parser = new RepositoryMarkdownParser();
        String source = "---\nroute: /existing-address\n---\n# Article";
        assertThat(parser.parse("public/article.md", source).route()).isEqualTo("/existing-address");
        assertThat(parser.parse("private/article.md", source).route()).isEqualTo("/existing-address");
        assertThat(RepositoryPathRules.privatePath("private/article.md")).isTrue();
        assertThat(parser.parse("public/index.md", "# Home").route()).isEqualTo("/");
    }
}
