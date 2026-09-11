package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdministratorSetupTests {
    @Test
    void passwordArgumentsAreRejectedBeforeDatabaseOrConsoleAccess() {
        assertThat(AdministratorSetup.run(new String[] {"admin", "init", "--password", "not-accepted"}))
                .isEqualTo(2);
    }

    @Test
    void nonInteractiveProcessesCannotSupplyOrReadPasswordInput() {
        assertThat(System.console()).isNull();
        assertThat(AdministratorSetup.run(new String[] {"admin", "init"})).isEqualTo(2);
    }
}
