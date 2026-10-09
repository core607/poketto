package io.github.core607.poketto.plaza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlazaCommandTests {
    @Test
    void quotesAreLiteralAndShellLookingTextIsNeverExpanded() {
        var command = PlazaCommand.parse("note \"$(whoami); | > 'hello'\" abc");
        assertThat(command.name()).isEqualTo("note");
        assertThat(command.arguments()).containsExactly("$(whoami); | > 'hello'", "abc");
        assertThat(PlazaCommand.parse("read 'space/a b' 10").arguments()).containsExactly("space/a b", "10");
        assertThat(PlazaCommand.parse("rumor \"say \\\"hello\\\"\"").argument(0))
                .isEqualTo("say \"hello\"");
    }

    @Test
    void refusesMultilineMalformedAndOversizedInputBeforeDispatch() {
        for (String input : new String[] {
            "look\nknock", "\n", "read 'unfinished", "look\\", "\u0000", "note \ud800 x", "猫".repeat(2800)
        }) {
            assertThatThrownBy(() -> PlazaCommand.parse(input)).isInstanceOf(PlazaException.class);
        }
        assertThat(PlazaCommand.parse(" ").name()).isEqualTo("--help");
    }
}
