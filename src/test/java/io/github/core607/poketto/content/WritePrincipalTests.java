package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WritePrincipalTests {

    @Test
    void rendersTheCommitTrailerValue() {
        assertThat(new WritePrincipal(PrincipalType.API_KEY, "key-01HQ8").trailerValue())
                .isEqualTo("api-key:key-01HQ8");
        assertThat(new WritePrincipal(PrincipalType.ACCOUNT, "acct-7").trailerValue())
                .isEqualTo("account:acct-7");
        assertThat(WritePrincipal.SYSTEM.trailerValue()).isEqualTo("system:poketto");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "alice@example.com",
                "Alice Smith",
                "-leading-hyphen",
                "has/slash",
                "has:colon",
                "has\nnewline",
                ""
            })
    void refusesAnIdentifierThatCouldCarryPersonalDataOrBreakTheTrailer(String identifier) {
        assertThatThrownBy(() -> new WritePrincipal(PrincipalType.ACCOUNT, identifier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opaque token");
    }

    @Test
    void refusesAnIdentifierLongerThanSixtyFourCharacters() {
        assertThatThrownBy(() -> new WritePrincipal(PrincipalType.ACCOUNT, "a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new WritePrincipal(PrincipalType.ACCOUNT, "a".repeat(64)).identifier())
                .hasSize(64);
    }

    @Test
    void parsesOnlyKnownPrincipalTypes() {
        assertThat(PrincipalType.parse("api-key")).isEqualTo(PrincipalType.API_KEY);
        assertThatThrownBy(() -> PrincipalType.parse("API_KEY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown principal type");
    }
}
