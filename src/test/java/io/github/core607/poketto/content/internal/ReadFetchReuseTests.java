package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFetchReuseTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final AtomicInteger fetches = new AtomicInteger();

    @Test
    void readsOfTheLatestMainWithinTheWindowShareOneFetch() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, counting(), Duration.ofMinutes(5));
        var first = fixture.commitRemote(workspace, Map.of("private/a.md", bytes("a")));
        assertThat(head(fixture, Optional.empty())).isEqualTo(first.name());
        var second = fixture.commitRemote(workspace, Map.of("private/b.md", bytes("b")));

        assertThat(head(fixture, Optional.empty())).isEqualTo(first.name());
        assertThat(fetches).hasValue(1);

        // A reader naming a commit the cache has not recorded still fetches it.
        assertThat(head(fixture, Optional.of(second.name()))).isEqualTo(second.name());
        assertThat(fetches).hasValue(2);
    }

    @Test
    void withoutAWindowEveryReadOfTheLatestMainFetches() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, counting(), Duration.ZERO);
        fixture.commitRemote(workspace, Map.of("private/a.md", bytes("a")));
        head(fixture, Optional.empty());
        var second = fixture.commitRemote(workspace, Map.of("private/b.md", bytes("b")));

        assertThat(head(fixture, Optional.empty())).isEqualTo(second.name());
        assertThat(fetches).hasValue(2);
    }

    private String head(RemoteRepositoryFixture fixture, Optional<String> known) {
        return fixture.authority()
                .readObjects(workspace, known, snapshot -> snapshot.commitId().orElseThrow());
    }

    private RemoteGitTransport counting() {
        var transport = new JGitRemoteGitTransport();
        return new RemoteGitTransport() {
            @Override
            public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
                fetches.incrementAndGet();
                return transport.fetchMain(repository, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
                return transport.pushMain(repository, binding, expected, candidate);
            }
        };
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
