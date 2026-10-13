package io.github.core607.poketto.games.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GamePackagesTests {
    private final WorkspaceId workspace = WorkspaceId.random();
    private final UUID articleId = UUID.fromString("69eec28b-8e1e-458f-a4a1-951a3b6d6bd4");
    private final RepositoryBlobReader blobs = mock(RepositoryBlobReader.class);
    private final HashMap<String, byte[]> files = new HashMap<>();
    private final HashMap<String, Boolean> published = new HashMap<>();
    private final GamePackages packages = new GamePackages(blobs);

    @BeforeEach
    void setup() throws Exception {
        for (String name : List.of("index.md.game.json", "rules.mjs", "display.mjs")) {
            files.put("public/puzzle/" + name, Files.readAllBytes(Path.of("examples/pocket-game/" + name)));
        }
        when(blobs.find(any(), anyString(), anyString())).thenAnswer(call -> {
            String path = call.getArgument(2);
            byte[] bytes = files.get(path);
            return bytes == null
                    ? Optional.empty()
                    : Optional.of(new RepositoryBlob(
                            workspace,
                            call.getArgument(1),
                            path,
                            "b".repeat(40),
                            bytes.length,
                            published.getOrDefault(path, true)));
        });
        when(blobs.read(any())).thenAnswer(call -> files.get(((RepositoryBlob) call.getArgument(0)).path()));
    }

    @Test
    void packageVersionFollowsItsBytesAndNotUnrelatedGitChanges() {
        GamePackages.Package first = packages.find(snapshot("a"), article()).orElseThrow();
        GamePackages.Package otherCommit =
                packages.find(snapshot("c"), article()).orElseThrow();
        assertThat(first.version()).isEqualTo(otherCommit.version());
        assertThat(first.bundle().source()).contains("export function act");
        files.put("public/puzzle/rules.mjs", "export function init(){}".getBytes(StandardCharsets.UTF_8));
        assertThat(packages.find(snapshot("c"), article()).orElseThrow().version())
                .isNotEqualTo(first.version());
    }

    @Test
    void excludedAndMissingRulesNeverBecomePublicPackages() {
        published.put("public/puzzle/rules.mjs", false);
        assertThatThrownBy(() -> packages.find(snapshot("a"), article()))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("missing or not public");
        published.clear();
        files.remove("public/puzzle/rules.mjs");
        assertThatThrownBy(() -> packages.find(snapshot("a"), article())).isInstanceOf(GameException.class);
        files.remove("public/puzzle/index.md.game.json");
        assertThat(packages.find(snapshot("a"), article())).isEmpty();
    }

    @Test
    void manifestCannotSelectParentPrivateAbsoluteOrHiddenPaths() {
        for (String path : List.of("../secret.mjs", "/private/secret.mjs", ".git/config", "sub/.hidden")) {
            assertThatThrownBy(() -> new GameManifest(1, articleId, path, null, "Help", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new GameManifest(1, articleId, "rules.mjs", null, "", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedFilesAndMismatchedArticleIdentityAreRejected() {
        files.put("public/puzzle/rules.mjs", new byte[256 * 1024 + 1]);
        assertThatThrownBy(() -> packages.find(snapshot("a"), article())).isInstanceOf(GameException.class);
        String manifest = new String(files.get("public/puzzle/index.md.game.json"), StandardCharsets.UTF_8);
        files.put(
                "public/puzzle/index.md.game.json",
                manifest.replace(articleId.toString(), UUID.randomUUID().toString())
                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> packages.find(snapshot("a"), article())).hasMessageContaining("article ID");
    }

    private PublicArticle article() {
        return new PublicArticle(
                "public/puzzle/index.md",
                "/puzzle",
                "Puzzle",
                "Intro",
                List.of(),
                Instant.now(),
                Instant.now(),
                true,
                "Author",
                articleId,
                false);
    }

    private PublicContentSnapshot snapshot(String commit) {
        return new PublicContentSnapshot(
                workspace,
                Optional.of(commit.repeat(40)),
                Instant.now(),
                Instant.now().plusSeconds(300),
                List.of(article()));
    }
}
