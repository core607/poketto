package io.github.core607.poketto;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
            "poketto.workspace.catalog.enabled=false",
            "spring.autoconfigure.exclude=" + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
class PokettoApplicationTests {

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void contentProperties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", () -> dataDirectory.toAbsolutePath().toString());
    }

    @Test
    void contextLoads() {}
}
