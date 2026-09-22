package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ArticleIdentityDrafts;
import io.github.core607.poketto.content.ContentLimits;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArticleIdentityDraftsTests {
    private final RepositoryMarkdownConfiguration configuration = new RepositoryMarkdownConfiguration();
    private final ArticleIdentityDrafts drafts =
            configuration.articleIdentityDrafts(configuration.repositoryMarkdownInspector());

    @Test
    void insertsWithoutReformattingBomCrLfUnknownMetadataOrBody() {
        String source = "\ufeff---\r\n# keep this comment\r\ntags: [日常]\r\ncustom: 'retain me'\r\n---\r\n# 原文\r\n";
        ArticleIdentityDrafts.Draft draft = drafts.prepare("private/note.md", source);
        assertThat(draft.source()).isEqualTo("\ufeff---\r\nid: " + draft.articleId() + "\r\n" + source.substring(6));
        assertThat(drafts.prepare("private/note.md", draft.source())).isEqualTo(draft);
        assertThat(new RepositoryMarkdownParser()
                        .parse("private/note.md", draft.source())
                        .body())
                .isEqualTo("# 原文\r\n");
    }

    @Test
    void addsOnlyFrontmatterToPlainMarkdownAndPreservesQuotedExistingId() {
        for (String source : new String[] {"", "# Plain\n", "\ufeff# 原文\r\n"}) {
            ArticleIdentityDrafts.Draft draft = drafts.prepare("private/note.md", source);
            assertThat(draft.articleId()).isNotNull();
            assertThat(draft.source()).endsWith(source.replace("\ufeff", ""));
            assertThat(drafts.prepare("private/note.md", draft.source())).isEqualTo(draft);
        }
        UUID id = UUID.randomUUID();
        String source = "---\n'id': '" + id + "' # kept\n---\n# Body";
        assertThat(drafts.prepare("private/note.md", source)).isEqualTo(new ArticleIdentityDrafts.Draft(source, id));
    }

    @Test
    void refusesToReplaceAnAuthoredInvalidIdButKeepsTheArticleReadable() {
        for (String value : new String[] {
            "old-slug", "42", "null", "[one, two]", "1-1-1-1-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        }) {
            String source = "---\nid: " + value + "\n---\n# Legacy";
            RepositoryMarkdownParser.Metadata metadata = new RepositoryMarkdownParser().parse("public/note.md", source);
            assertThat(metadata.articleId()).isNull();
            assertThat(metadata.invalidArticleId()).isTrue();
            assertThat(metadata.title()).isEqualTo("Legacy");
            assertThatThrownBy(() -> drafts.prepare("public/note.md", source))
                    .hasMessageContaining("existing article id");
        }
    }

    @Test
    void validatesSourceAndOutputSizeIncludingAddedFrontmatter() {
        assertThatThrownBy(() -> drafts.prepare("../escape.md", "# Body")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.prepare("private/image.png", "# Body"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.prepare("private/note.md", "---\nid: [\n---\n# Body"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.prepare("private/note.md", "# Body\n\ud800"))
                .isInstanceOf(IllegalArgumentException.class);
        String fullDocument = "# Body\n" + "a".repeat(ContentLimits.MAX_DOCUMENT_BYTES - 7);
        assertThatThrownBy(() -> drafts.prepare("private/note.md", fullDocument))
                .hasMessageContaining("bounds");
        String fullFrontmatter =
                "---\ncustom: " + "a".repeat(ContentLimits.MAX_FRONTMATTER_BYTES - 9) + "\n---\n# Body";
        assertThatThrownBy(() -> drafts.prepare("private/note.md", fullFrontmatter))
                .hasMessageContaining("frontmatter exceeds");
    }
}
