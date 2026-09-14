package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RetainedOriginalLookupTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final UUID copy = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = mock(Clock.class);

    @BeforeEach
    void authorize() {
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(clock.millis()).thenAnswer(call -> now.get());
        when(auth.authorize(any(), any()))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
    }

    @Test
    void savesOriginalAndAbsentPathsAfterStateCopiesWithoutHistoricalReadsAndReusesOneDescriptor() throws Exception {
        try (var fixture = fixture()) {
            var stores = stores();
            var originals = spy(stores.originals());
            var reader = unavailableHistory(fixture);
            var saves = saves(fixture, reader);
            var command = admitted(fixture, stores.records(), originals);
            var state = state(fixture, command, originals);
            try (command) {
                assertThat(saves.save(actor, workspace, state, Map.of("private/secret.md", "first save"), List.of())
                                .ok())
                        .isTrue();
                assertThat(saves.baselineFile(actor, workspace, state.copy(), "AGENTS.md")
                                .source())
                        .contains("operator-secret-needle");
                assertThat(saves.save(actor, workspace, state, Map.of("private/new.md", "new file"), List.of())
                                .ok())
                        .isTrue();
                assertThat(saves.save(actor, workspace, state, Map.of("AGENTS.md", "last save"), List.of())
                                .ok())
                        .isTrue();
                verify(originals, times(1)).open(any(), any());
                verifyNoInteractions(reader);
                command.complete(state.snapshot());
            }
            assertThat(fixture.reader(auth)
                            .getFile(actor, workspace, Optional.empty(), "private/new.md")
                            .source())
                    .contains("new file");
            assertThat(fixture.pushes()).isEqualTo(3);
            assertThatThrownBy(() -> saves.baselineFile(actor, workspace, state, "never-created.md"))
                    .isInstanceOf(RetainedCopyException.class);
            try (var nextWriter = stores.records().writer(owner(), copy)) {
                nextWriter.requireValid();
            }
        }
    }

    @Test
    void remoteAdvanceStillConflictsAndSyncUsesTheArchivedOriginalBeforeRetainingTheRemoteVersion() throws Exception {
        try (var fixture = fixture()) {
            var stores = stores();
            try (var command = admitted(fixture, stores.records(), stores.originals())) {
                var state = state(fixture, command, stores.originals());
                var live = saves(fixture, fixture.reader(auth));
                var competitor = new SelectedFileSaves.State(fixture.sourceCommit());
                assertThat(live.save(actor, workspace, competitor, Map.of("AGENTS.md", "remote changed"), List.of())
                                .ok())
                        .isTrue();
                var saves = saves(fixture, unavailableHistory(fixture));
                assertThat(saves.save(actor, workspace, state, Map.of("AGENTS.md", "local changed"), List.of())
                                .code())
                        .isEqualTo("REPOSITORY_CONFLICT");
                var plan = saves.prepareSync(actor, workspace, state, "AGENTS.md", Optional.of("local changed"));
                assertThat(plan.conflicted()).isTrue();
                assertThat(plan.remoteSource()).contains("remote changed");
                saves.acknowledgeSync(state, plan);
                assertThat(saves.baselineFile(actor, workspace, state, "AGENTS.md")
                                .source())
                        .contains("remote changed");
                assertThat(saves.baselineFile(actor, workspace, state, "private/secret.md")
                                .source())
                        .contains("current-secret-needle");
                assertThat(saves.save(actor, workspace, state, Map.of("AGENTS.md", "resolved"), List.of())
                                .ok())
                        .isTrue();
                assertThat(fixture.pushes()).isEqualTo(2);
            }
        }
    }

    @Test
    void missingArchiveCannotBecomeKnownAbsenceOrFallBackToRepositoryHistory() throws Exception {
        try (var fixture = fixture()) {
            var stores = stores();
            try (var command = admitted(fixture, stores.records(), stores.originals())) {
                var state = state(fixture, command, stores.originals());
                var reader = unavailableHistory(fixture);
                var saves = saves(fixture, reader);
                Files.delete(archive());
                assertThatThrownBy(
                                () -> saves.save(actor, workspace, state, Map.of("new.md", "must not push"), List.of()))
                        .isInstanceOf(RetainedCopyException.class);
                verifyNoInteractions(reader);
                assertThat(fixture.pushes()).isZero();
            }
        }
    }

    @Test
    void openArchiveStillRequiresCurrentPermissionBeforeAndAfterEveryRead() throws Exception {
        try (var fixture = fixture()) {
            var stores = stores();
            try (var command = admitted(fixture, stores.records(), stores.originals())) {
                var state = state(fixture, command, stores.originals());
                var saves = saves(fixture, unavailableHistory(fixture));
                assertThat(saves.baselineFile(actor, workspace, state, "AGENTS.md")
                                .source())
                        .isPresent();
                doThrow(new AuthException(AuthException.Code.DENIED))
                        .when(auth)
                        .withAuthorization(eq(actor), eq(workspace), eq(Set.of(Capability.READ_PRIVATE)), any());
                assertThatThrownBy(() -> saves.baselineFile(actor, workspace, state, "private/secret.md"))
                        .isInstanceOf(AuthException.class);
                assertThatThrownBy(() -> saves.baselineFile(actor, workspace, state, "new.md"))
                        .isInstanceOf(AuthException.class);
                var revoked = new AuthException(AuthException.Code.DENIED);
                doThrow(revoked).when(auth).authorize(actor, workspace, Capability.READ_PRIVATE);
                assertThatThrownBy(() -> saves.baselineFile(actor, workspace, state, "AGENTS.md"))
                        .isSameAs(revoked);
            }
        }
    }

    @Test
    void openedReaderRejectsExpiredOrMismatchedAuthorityAndCommandCloseReleasesItsFile() throws Exception {
        try (var fixture = fixture()) {
            var stores = stores();
            var command = admitted(fixture, stores.records(), stores.originals());
            var lookup = command.originals(stores.originals());
            Path archive = archive();
            try (command) {
                assertThat(lookup.file(actor, workspace, fixture.sourceCommit(), "AGENTS.md")
                                .source())
                        .isPresent();
                assertThat(openDescriptors(archive)).isEqualTo(1);
                assertThatThrownBy(() -> lookup.file(actor, WorkspaceId.random(), fixture.sourceCommit(), "new.md"))
                        .isInstanceOf(RetainedCopyException.class);
                assertThatThrownBy(() -> lookup.file(actor, workspace, "f".repeat(40), "new.md"))
                        .isInstanceOf(RetainedCopyException.class);
                var another = mock(AuthPrincipal.class);
                when(another.subjectId()).thenReturn(UUID.randomUUID());
                assertThatThrownBy(() -> lookup.file(another, workspace, fixture.sourceCommit(), "new.md"))
                        .isInstanceOf(RetainedCopyException.class);
                now.addAndGet(Duration.ofHours(2).toMillis());
                assertThatThrownBy(() -> lookup.file(actor, workspace, fixture.sourceCommit(), "new.md"))
                        .isInstanceOfSatisfying(
                                RetainedCopyException.class,
                                failure ->
                                        assertThat(failure.reason()).isEqualTo(RetainedCopyException.Reason.EXPIRED));
            }
            assertThat(openDescriptors(archive)).isZero();
        }
    }

    private long openDescriptors(Path target) throws Exception {
        long count = 0;
        try (var paths = Files.newDirectoryStream(Path.of("/proc/self/fd"))) {
            for (Path path : paths) {
                if (Files.readSymbolicLink(path).equals(target)) {
                    count++;
                }
            }
        }
        return count;
    }

    private Path archive() throws Exception {
        try (var paths = Files.newDirectoryStream(root.resolve("baseline-store"), "*.baseline")) {
            return paths.iterator().next();
        }
    }

    private RetainedCopyRecord.Owner owner() {
        return new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
    }

    private RetainedWorkStores stores() {
        var records = new RetainedCopyStore(
                root.resolve("metadata"),
                new RetainedCopyStore.Limits(4, 1024 * 1024, 8 * 1024 * 1024, 0, Duration.ofHours(1)),
                clock);
        return RetainedBaselineTestData.stores(records, root.resolve("baseline-store"));
    }

    private RetainedCommand admitted(
            PublicExecutionNativeFixture fixture, RetainedCopyStore records, RetainedBaselineStore originals) {
        var command = new RetainedCommand(records, owner(), copy, null, port(fixture.sourceCommit()));
        boolean admitted = false;
        try {
            command.initialize(
                    "a".repeat(64),
                    true,
                    null,
                    new RetainedCopyRecord.Writer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                    new SelectedFileSaves.State(fixture.sourceCommit()).snapshot(),
                    originals,
                    sink -> fixture.reader(auth)
                            .visitBaseline(
                                    actor, workspace, fixture.sourceCommit(), originals.traversalLimits(), sink));
            command.begin(UUID.randomUUID());
            admitted = true;
            return command;
        } finally {
            if (!admitted) {
                command.close();
            }
        }
    }

    private SelectedFileSaves.State state(
            PublicExecutionNativeFixture fixture, RetainedCommand command, RetainedBaselineStore originals) {
        return SelectedFileSaves.State.restore(
                new SelectedFileSaves.State(fixture.sourceCommit()).snapshot(),
                command::retain,
                command.originals(originals));
    }

    private static RetainedCommand.Port port(String commit) {
        return new RetainedCommand.Port() {
            @Override
            public WorkerResponses.CheckpointReply capture(UUID id, long expiry, Optional<UUID> execution) {
                return new WorkerResponses.CheckpointReply(
                        true,
                        new UUID(0, 1).toString(),
                        commit,
                        execution.isPresent() ? "RUNNING" : "READY",
                        execution.map(UUID::toString).orElse(null),
                        new WorkerResponses.CheckpointDescriptor(id.toString(), "b".repeat(64), 128, expiry));
            }

            @Override
            public void remove(RetainedCopyRecord.Checkpoint checkpoint) {}
        };
    }

    private PublicExecutionNativeFixture fixture() throws Exception {
        return new PublicExecutionNativeFixture(root.resolve("repository"), root.resolve("exports"), auth, workspace);
    }

    private SelectedFileSaves saves(PublicExecutionNativeFixture fixture, AuthorizedRepositoryReader reader) {
        return new SelectedFileSaves(auth, reader, fixture.patches(auth), fixture.moves(auth));
    }

    private AuthorizedRepositoryReader unavailableHistory(PublicExecutionNativeFixture fixture) {
        var reader = mock(AuthorizedRepositoryReader.class);
        var current = fixture.reader(auth);
        when(reader.getFile(any(), any(), any(), any())).thenAnswer(call -> {
            Optional<String> commit = call.getArgument(2);
            if (commit.isPresent()) {
                throw new ContentRepositoryException("historical baseline reader unavailable");
            }
            return current.getFile(call.getArgument(0), call.getArgument(1), commit, call.getArgument(3));
        });
        return reader;
    }
}
