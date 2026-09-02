package io.github.core607.poketto.content.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("poketto")
record ContentProperties(Path dataDir) {

    ContentProperties {
        if (dataDir == null) {
            throw new IllegalArgumentException("poketto.data-dir must be configured as an absolute path");
        }
        if (!dataDir.isAbsolute()) {
            throw new IllegalArgumentException("poketto.data-dir must be absolute: " + dataDir);
        }
        dataDir = dataDir.normalize();
    }
}
