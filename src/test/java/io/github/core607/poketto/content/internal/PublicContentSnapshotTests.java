package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicContentSnapshotTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();
    private final MutableClock clock = new MutableClock();
    private final SwitchableTransport transport = new SwitchableTransport();

    @Test
    void applicationInitializationKeepsRetryingWhenContentCannotBeServed() throws Exception {
        var retries = new java.util.concurrent.CountDownLatch(1);
        var snapshots = org.mockito.Mockito.mock(io.github.core607.poketto.content.PublicContentSnapshots.class);
        org.mockito.Mockito.doThrow(new ContentRepositoryException("unavailable"))
                .when(snapshots)
                .ensureReady(workspace);
        try (var refresher = new ContentSnapshotRefresher(
                ignored -> retries.countDown(), () -> java.util.List.of(workspace), Duration.ofMillis(10))) {
            new ContentConfiguration()
                    .contentRepositoryInitializer(() -> workspace, snapshots, refresher)
                    .run(new org.springframework.boot.DefaultApplicationArguments());
            assertThat(retries.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void privateRouteCollisionCannotChangePublicAvailability() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        policy(true),
                        "article.md",
                        text("# Public"),
                        "private/secret.md",
                        text("---\nroute: /article\n---\n# Private")));
        var snapshots = snapshots(fixture);
        assertThat(snapshots.refresh(workspace).articles())
                .extracting(article -> article.title())
                .containsExactly("Public");
        fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "article.md", text("# Public")));
        assertThat(snapshots.refresh(workspace).articles())
                .extracting(article -> article.title())
                .containsExactly("Public");
    }

    @Test
    void unconfiguredAndDisabledPoliciesPublishNothing() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        fixture.commitRemote(workspace, Map.of("hello.md", text("# Hello")));
        var snapshots = snapshots(fixture);
        assertThat(snapshots.refresh(workspace).articles()).isEmpty();
        fixture.commitRemote(
                workspace, Map.of("hello.md", text("# Hello"), RepositoryPublishingPolicy.PATH, policy(false)));
        assertThat(snapshots.refresh(workspace).articles()).isEmpty();
        assertThat(new PublicSnapshotHealthIndicator(snapshots, () -> workspace)
                        .health()
                        .getStatus()
                        .getCode())
                .isEqualTo("UP");
    }

    @Test
    void publicTreeIsOneCommitAndFiltersPrivateExcludedReservedAndMalformedFiles() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var commit = fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        text("enabled: true\nmode: public-by-default\nexclude: ['drafts/**']\n"),
                        "hello.md",
                        text("---\ntitle: Hello\nprivate_field: keep-secret\n---\nPublic body"),
                        "private/secret.md",
                        text("# Secret\nprivate body"),
                        "PRIVATE/another.md",
                        text("# Case secret"),
                        "drafts/work.md",
                        text("# Draft"),
                        "nested/.poketto/state.md",
                        text("# Internal"),
                        "documents/bad.md",
                        text("---\nbroken: [\n---\n"),
                        "image.png",
                        new byte[2_000_000]));
        var snapshots = snapshots(fixture);
        var snapshot = snapshots.refresh(workspace);
        assertThat(transport.fetches).isOne();
        assertThat(snapshot.commit()).contains(commit.name());
        assertThat(snapshot.articles()).hasSize(1);
        assertThat(snapshot.articles().getFirst().body()).isEqualTo("Public body");
        assertThat(snapshot.articles().toString())
                .doesNotContain("keep-secret", "private body", "Draft", "Internal", "broken");
        assertThat(fixture.cache(workspace).resolve("image.png")).doesNotExist();
        snapshots.current(workspace);
        assertThat(transport.fetches).isOne();
    }

    @Test
    void aConcurrentRemoteAdvanceCannotMixNewPolicyWithEarlierDocuments() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var old = fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "hello.md", text("# Old public")));
        transport.afterFetch = () -> {
            try {
                fixture.commitRemote(
                        workspace,
                        Map.of(RepositoryPublishingPolicy.PATH, policy(false), "hello.md", text("# New private")));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        };
        var snapshots = snapshots(fixture);
        var observed = snapshots.refresh(workspace);
        assertThat(observed.commit()).contains(old.name());
        assertThat(observed.articles().getFirst().title()).isEqualTo("Old public");
        assertThat(transport.fetches).isOne();
        assertThat(snapshots.refresh(workspace).articles()).isEmpty();
    }

    @Test
    void invalidPolicyRevokesOldSnapshotImmediatelyAndSurvivesOfflineRestart() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "hello.md", text("# Hello")));
        var snapshots = snapshots(fixture);
        snapshots.refresh(workspace);
        fixture.commitRemote(
                workspace,
                Map.of(RepositoryPublishingPolicy.PATH, text("enabled: [secret"), "hello.md", text("# Hello")));
        assertThatThrownBy(() -> snapshots.refresh(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> snapshots.current(workspace)).isInstanceOf(ContentRepositoryException.class);
        transport.offline = true;
        var restarted = snapshots(fixture);
        assertThatThrownBy(() -> restarted.ensureReady(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> restarted.current(workspace)).isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void outageNeverRenewsExpiryAndBoundaryTurnsReadinessUnavailable() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "hello.md", text("# Hello")));
        var snapshots = snapshots(fixture);
        Instant verifiedAt = snapshots.refresh(workspace).verifiedAt();
        transport.offline = true;
        clock.now = clock.now.plusSeconds(3599);
        assertThatThrownBy(() -> snapshots.refresh(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThat(snapshots.current(workspace).verifiedAt()).isEqualTo(verifiedAt);
        clock.now = clock.now.plusSeconds(1);
        assertThatThrownBy(() -> snapshots.current(workspace)).isInstanceOf(ContentRepositoryException.class);
        assertThat(new PublicSnapshotHealthIndicator(snapshots, () -> workspace)
                        .health()
                        .getStatus()
                        .getCode())
                .isEqualTo("OUT_OF_SERVICE");
        transport.offline = false;
        assertThat(snapshots.refresh(workspace).verifiedAt()).isEqualTo(clock.now);
        assertThat(new PublicSnapshotHealthIndicator(snapshots, () -> workspace)
                        .health()
                        .getStatus()
                        .getCode())
                .isEqualTo("UP");
    }

    @Test
    void offlineRestartRestoresOriginalVerificationTimeAndRejectsExpiredCache() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "hello.md", text("# Hello")));
        var original = snapshots(fixture).refresh(workspace);
        transport.offline = true;
        clock.now = clock.now.plusSeconds(1800);
        var restarted = snapshots(fixture);
        restarted.ensureReady(workspace);
        assertThat(restarted.current(workspace).verifiedAt()).isEqualTo(original.verifiedAt());
        assertThat(restarted.current(workspace).expiresAt()).isEqualTo(original.expiresAt());
        clock.now = original.expiresAt();
        assertThatThrownBy(() -> snapshots(fixture).ensureReady(workspace))
                .isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void crashAfterObservingNewMainCannotRestoreEarlierPublicationMarker() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "hello.md", text("# Hello")));
        snapshots(fixture).refresh(workspace);
        fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(false), "hello.md", text("# Withdrawn")));
        fixture.authority().readObjects(workspace, ignored -> null);
        transport.offline = true;
        assertThatThrownBy(() -> snapshots(fixture).ensureReady(workspace))
                .isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void acknowledgedSnapshotInstallDoesNotFetchAndDoesNotChangeOriginalObjects() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory, transport);
        var commit = fixture.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, policy(true), "hello.md", text("# Hello")));
        var snapshots = snapshots(fixture);
        fixture.authority().readObjects(workspace, snapshot -> snapshots.installAcknowledged(workspace, snapshot));
        assertThat(transport.fetches).isOne();
        assertThat(snapshots.current(workspace).commit()).contains(commit.name());
        assertThat(fixture.remoteHead(workspace)).isEqualTo(commit);
    }

    private JGitPublicContentSnapshots snapshots(RemoteRepositoryFixture fixture) {
        return new JGitPublicContentSnapshots(fixture.authority(), clock, Duration.ofHours(1));
    }

    private static byte[] policy(boolean enabled) {
        return text("enabled: " + enabled + "\nmode: public-by-default\n");
    }

    private static byte[] text(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final class SwitchableTransport implements RemoteGitTransport {
        final RemoteGitTransport delegate = new JGitRemoteGitTransport();
        int fetches;
        boolean offline;
        Runnable afterFetch;

        @Override
        public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
            fetches++;
            if (offline) throw new RemoteGitTransportException("fetch");
            ObjectId commit = delegate.fetchMain(repository, binding);
            if (afterFetch != null) {
                Runnable action = afterFetch;
                afterFetch = null;
                action.run();
            }
            return commit;
        }

        @Override
        public PushStatus pushMain(
                Repository repository, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
            return delegate.pushMain(repository, binding, expected, candidate);
        }
    }
}
