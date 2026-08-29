package io.github.core607.poketto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "poketto.workspace.catalog.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class PokettoApplicationTests {

    @Test
    void contextLoads() {
    }
}
