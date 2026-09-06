package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.RepositoryMarkdownInspector;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RepositoryMarkdownConfiguration {
    @Bean
    RepositoryMarkdownInspector repositoryMarkdownInspector() {
        RepositoryMarkdownParser parser = new RepositoryMarkdownParser();
        return (path, source) -> {
            RepositoryPathRules.validate(path);
            if (!RepositoryPathRules.markdown(path))
                throw new IllegalArgumentException("preview requires a Markdown path");
            if (source == null || source.length() > ContentLimits.MAX_DOCUMENT_BYTES)
                throw new IllegalArgumentException("preview source exceeds its bounds");
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(source));
                if (encoded.remaining() > ContentLimits.MAX_DOCUMENT_BYTES)
                    throw new IllegalArgumentException("preview source exceeds its bounds");
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                RepositoryMarkdownParser.decode(bytes);
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("preview source is not valid UTF-8");
            }
            var parsed = parser.parse(path, source);
            return new RepositoryMarkdownInspector.Draft(
                    parsed.body(), parsed.route(), RepositoryPathRules.folderPage(path));
        };
    }
}
