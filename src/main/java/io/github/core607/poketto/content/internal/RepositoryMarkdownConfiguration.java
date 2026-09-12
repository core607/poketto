package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.RepositoryMarkdownInspector;
import java.nio.charset.CharacterCodingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RepositoryMarkdownConfiguration {
    @Bean
    RepositoryMarkdownInspector repositoryMarkdownInspector() {
        RepositoryMarkdownParser parser = new RepositoryMarkdownParser();
        return (path, source) -> {
            RepositoryPathRules.validate(path);
            if (!RepositoryPathRules.markdown(path)) {
                throw new IllegalArgumentException("preview requires a Markdown path");
            }
            if (source == null || source.length() > ContentLimits.MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("preview source exceeds its bounds");
            }
            try {
                RepositoryMarkdownParser.decode(
                        StrictText.utf8(source, ContentLimits.MAX_DOCUMENT_BYTES, "preview source exceeds its bounds"));
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("preview source is not valid UTF-8");
            }
            var parsed = parser.parse(path, source);
            return new RepositoryMarkdownInspector.Draft(
                    parsed.body(), parsed.route(), RepositoryPathRules.folderPage(path));
        };
    }
}
