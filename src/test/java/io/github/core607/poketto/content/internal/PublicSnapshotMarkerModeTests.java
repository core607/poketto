package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicSnapshotMarkerModeTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void windowsModeAllowsFreshOnlineReadsButNeverRestoresAnyDiskAuthorization() throws Exception {
        var transport = new SwitchableTransport();
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var workspace = WorkspaceId.random();
        var commit = fixture.commitRemote(workspace, files());
        var onlineOnly = new PublicSnapshotMarker(false, path -> {
            throw new AssertionError("Windows mode must not claim directory synchronization");
        });
        var snapshots = snapshots(fixture, onlineOnly);
        assertThat(snapshots.refresh(workspace).articles()).hasSize(1);
        Path marker = fixture.cache(workspace).resolve(".git/" + PublicSnapshotMarker.NAME);
        assertThat(Files.readString(marker)).startsWith("ONLINE_ONLY ");
        for (String prefix : new String[] {"", "DURABLE_V2 ", "ONLINE_ONLY "}) {
            Files.writeString(marker, prefix + commit.name() + " " + CLOCK.instant() + " OPEN\n");
            transport.offline = true;
            var restarted = snapshots(fixture, onlineOnly);
            assertThatThrownBy(() -> restarted.ensureReady(workspace)).isInstanceOf(ContentRepositoryException.class);
            assertThatThrownBy(() -> restarted.current(workspace)).isInstanceOf(ContentRepositoryException.class);
            transport.offline = false;
            assertThat(restarted.refresh(workspace).articles()).hasSize(1);
        }
    }

    @Test
    void productionPlatformPolicyDoesNotTreatLinuxIoFailureAsWindowsMode() {
        assertThat(new PublicSnapshotMarker().supportsOfflineRestoration())
                .isEqualTo(!System.getProperty("os.name").startsWith("Windows"));
    }

    private JGitPublicContentSnapshots snapshots(RemoteRepositoryFixture fixture, PublicSnapshotMarker marker) {
        return new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1), marker);
    }

    static Map<String, byte[]> files() {
        return Map.of(
                RepositoryPublishingPolicy.PATH,
                "enabled: true\nmode: public-by-default\n".getBytes(StandardCharsets.UTF_8),
                "hello.md",
                "# Hello".getBytes(StandardCharsets.UTF_8));
    }

    static class SwitchableTransport implements RemoteGitTransport {
        private final RemoteGitTransport delegate = new JGitRemoteGitTransport();
        boolean offline;

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            if (offline) throw new RemoteGitTransportException("synthetic offline authority");
            return delegate.fetchMain(repository, binding);
        }

        @Override
        public PushStatus pushMain(
                Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
            return delegate.pushMain(repository, binding, expected, candidate);
        }
    }
}
