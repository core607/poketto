package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RepositoryCoordinatesTests {
    @Test
    void repositoryAliasesShareCanonicalIdentityWithoutCredentialBearingCoordinates() {
        var github = RepositoryCoordinates.parse("https://GitHub.com:443/Example/Notes.git/");
        assertThat(github.canonicalUri()).isEqualTo("https://github.com/example/notes");
        assertThat(github.transportUri()).isEqualTo("https://github.com/example/notes.git");
        var cnb = RepositoryCoordinates.parse("https://cnb.cool/example/team/notes.git");
        assertThat(cnb.canonicalUri()).isEqualTo("https://cnb.cool/example/team/notes");
        assertThat(cnb.transportUri()).isEqualTo(cnb.canonicalUri());
        assertThat(cnb.toString()).doesNotContain("team", "notes");
    }

    @Test
    void userConnectionsCannotSelectOtherOriginsOrEscapeRepositoryCoordinates() {
        for (String input : new String[] {
            "http://github.com/example/notes", "https://127.0.0.1/example/notes",
                    "https://github.com.attacker.invalid/example/notes",
            "https://github.com:8443/example/notes", "https://user:secret@github.com/example/notes",
                    "https://github.com/example/notes?token=secret",
            "https://github.com/example/notes#secret", "https://github.com/example/notes/tree/main",
                    "https://github.com/example/%2e%2e",
            "https://cnb.cool/example//notes", "https://cnb.cool/example/../notes",
                    "https://cnb.cool/example/notes/-/settings",
            "https://cnb.cool/notes", "https://github.com/example/notes//", "https://github.com./example/notes"
        }) {
            assertThatThrownBy(() -> RepositoryCoordinates.parse(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret")
                    .hasMessageNotContaining("attacker");
        }
    }
}
