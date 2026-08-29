package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContentPropertiesTests {

    @Test
    void requiresAnExplicitAbsoluteDataDirectory() {
        Path absolute = Path.of(System.getProperty("java.io.tmpdir"), "poketto-data").toAbsolutePath();

        assertThat(new ContentProperties(absolute).dataDir()).isEqualTo(absolute.normalize());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentProperties(null))
                .withMessageContaining("must be configured as an absolute path");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentProperties(Path.of("relative-data")))
                .withMessageContaining("must be absolute");
    }
}
