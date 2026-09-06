package io.github.core607.poketto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void moduleStructureIsValid() {
        ApplicationModules modules =
                ApplicationModules.of(PokettoApplication.class).verify();

        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("assets", "auth", "content", "executor", "mcp", "qa", "workspace", "web");
    }
}
