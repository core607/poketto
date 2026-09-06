package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PublicSnapshotMarkerNativeTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void createsAndReplacesMarkerOnlyAfterSyncingItsContainingDirectories() throws Exception {
        Path git = Files.createDirectories(directory.resolve("workspace/content/.git"));
        List<Path> synchronizedPaths = new ArrayList<>();
        var marker = new PublicSnapshotMarker(true, path -> {
            // Exercise the real Linux primitive, recording completion rather than only invocation.
            PublicSnapshotMarker.syncDirectory(path);
            synchronizedPaths.add(path);
        });
        for (boolean open : new boolean[] {true, false, true}) {
            synchronizedPaths.clear();
            marker.write(git, "a".repeat(40), CLOCK.instant(), open);
            assertThat(synchronizedPaths)
                    .containsExactly(git, git.getParent(), git.getParent().getParent());
            assertThat(Files.readString(git.resolve(PublicSnapshotMarker.NAME)))
                    .isEqualTo(
                            "DURABLE_V2 " + "a".repeat(40) + " " + CLOCK.instant() + (open ? " OPEN\n" : " CLOSED\n"));
            assertThat(git.resolve(PublicSnapshotMarker.NAME + ".tmp")).doesNotExist();
        }
    }

    @Test
    void everyDirectorySyncFailureClosesPublicationAndPreventsTheRemotePush() throws Exception {
        for (int failAt = 1; failAt <= 3; failAt++) {
            var workspace = WorkspaceId.random();
            var fixture = new RemoteRepositoryFixture(directory.resolve("failure-" + failAt));
            var base = fixture.commitRemote(workspace, files());
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger failurePoint = new AtomicInteger(Integer.MAX_VALUE);
            var marker = new PublicSnapshotMarker(true, path -> {
                if (calls.incrementAndGet() == failurePoint.get())
                    throw new IOException("injected directory sync failure");
                PublicSnapshotMarker.syncDirectory(path);
            });
            var snapshots = new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1), marker);
            snapshots.refresh(workspace);
            calls.set(0);
            failurePoint.set(failAt);
            var auth = mock(AuthService.class);
            when(auth.withAuthorization(any(), any(), any(), any()))
                    .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
            var actor = mock(AuthPrincipal.class);
            when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
            when(actor.subjectId()).thenReturn(UUID.randomUUID());
            var patches = new JGitRepositoryPatchService(
                    fixture.authority(), auth, CLOCK, snapshots::installAcknowledged, snapshots::closePublication);
            var change = new RepositoryTextChange(
                    RepositoryPublishingPolicy.PATH,
                    false,
                    Optional.of(DocumentRevision.sha256(files().get(RepositoryPublishingPolicy.PATH))),
                    Optional.of("enabled: false\nmode: public-by-default\n"));
            assertThatThrownBy(() -> patches.apply(
                            actor, workspace, new RepositoryPatch(Optional.of(base.name()), List.of(change))))
                    .isInstanceOf(ContentRepositoryException.class)
                    .hasMessageContaining("cannot be recorded");
            assertThat(calls).hasValue(failAt);
            assertThat(fixture.remoteHead(workspace)).isEqualTo(base);
            assertThatThrownBy(() -> snapshots.current(workspace)).isInstanceOf(ContentRepositoryException.class);
            assertThat(Files.readString(fixture.cache(workspace).resolve(".git/" + PublicSnapshotMarker.NAME)))
                    .endsWith(" CLOSED\n");
        }
    }

    @Test
    void startupCannotRestoreAnOldOpenMarkerAfterItsClosedWriteFails() throws Exception {
        var workspace = WorkspaceId.random();
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, files());
        new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1)).refresh(workspace);
        Path git = fixture.cache(workspace).resolve(".git");
        Files.createDirectory(git.resolve(PublicSnapshotMarker.NAME + ".tmp"));
        var restarted = new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1));
        assertThatThrownBy(() -> restarted.ensureReady(workspace))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageContaining("cannot be recorded");
        assertThatThrownBy(() -> restarted.current(workspace)).isInstanceOf(ContentRepositoryException.class);
        // The old OPEN file still exists, so startup must distinguish local marker failure from outage.
        assertThat(Files.readString(git.resolve(PublicSnapshotMarker.NAME))).endsWith(" OPEN\n");
    }

    @Test
    void legacyAndWindowsMarkersNeverBecomeDurableOfflineAuthorization() throws Exception {
        var workspace = WorkspaceId.random();
        var transport = new PublicSnapshotMarkerModeTests.SwitchableTransport();
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var commit = fixture.commitRemote(workspace, files());
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1));
        snapshots.refresh(workspace);
        Path path = fixture.cache(workspace).resolve(".git/" + PublicSnapshotMarker.NAME);
        for (String prefix : new String[] {"", "ONLINE_ONLY "}) {
            Files.writeString(path, prefix + commit.name() + " " + CLOCK.instant() + " OPEN\n");
            transport.offline = true;
            var restarted = new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1));
            assertThatThrownBy(() -> restarted.ensureReady(workspace)).isInstanceOf(ContentRepositoryException.class);
            assertThatThrownBy(() -> restarted.current(workspace)).isInstanceOf(ContentRepositoryException.class);
            transport.offline = false;
            assertThat(restarted.refresh(workspace).articles()).hasSize(1);
        }
    }

    @Test
    void unsupportedDirectorySynchronizationCannotDowngradeProductionMode() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var workspace = WorkspaceId.random();
        fixture.commitRemote(workspace, files());
        var strict = new PublicSnapshotMarker(true, path -> {
            throw new UnsupportedOperationException("injected unsupported directory primitive");
        });
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1), strict);
        assertThatThrownBy(() -> snapshots.refresh(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThat(strict.supportsOfflineRestoration()).isTrue();
        assertThatThrownBy(() -> snapshots.current(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThat(Files.readString(fixture.cache(workspace).resolve(".git/" + PublicSnapshotMarker.NAME)))
                .startsWith("DURABLE_V2 ")
                .endsWith(" CLOSED\n");
    }

    @Test
    void failedOpenOrRenewalLeavesMemoryAndPersistedMarkerClosed() throws Exception {
        var workspace = WorkspaceId.random();
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, files());
        AtomicInteger calls = new AtomicInteger();
        var marker = new PublicSnapshotMarker(true, path -> {
            // Initial CLOSED and OPEN each synchronize three directories. Fail renewal's first sync.
            if (calls.incrementAndGet() == 7) throw new IOException("injected renewal sync failure");
            PublicSnapshotMarker.syncDirectory(path);
        });
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), CLOCK, Duration.ofHours(1), marker);
        snapshots.refresh(workspace);
        assertThatThrownBy(() -> snapshots.refresh(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> snapshots.current(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThat(Files.readString(fixture.cache(workspace).resolve(".git/" + PublicSnapshotMarker.NAME)))
                .endsWith(" CLOSED\n");
    }

    private static Map<String, byte[]> files() {
        return Map.of(
                RepositoryPublishingPolicy.PATH,
                "enabled: true\nmode: public-by-default\n".getBytes(StandardCharsets.UTF_8),
                "hello.md",
                "# Hello".getBytes(StandardCharsets.UTF_8));
    }
}
