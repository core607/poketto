package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import io.github.core607.poketto.mcp.ExecutionUnconfirmedException;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Real Git publication proofs drive the application lifecycle; the native peer records containment requests. */
class NativeRepositoryPublicTests extends NativeRepositoryTestFixture {
    private final AtomicLong time = new AtomicLong();
    private final AtomicBoolean fullRead = new AtomicBoolean();
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final AuthPrincipal publisher = mock(AuthPrincipal.class);
    private PublicExecutionNativeFixture publication;

    @BeforeEach
    void publicProjection() throws Exception {
        owner = new AccountCopyRecord.Owner(account, workspace.value(), false);
        when(publisher.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(publisher.accountId()).thenReturn(account);
        when(publisher.subjectId()).thenReturn(UUID.randomUUID());
        when(auth.siteGroup(any())).thenReturn(SiteGroup.COMMUNITY);
        when(auth.authorize(any(), any(), any(Capability[].class))).thenAnswer(call -> {
            AuthPrincipal actor = call.getArgument(0);
            Capability[] needed = (Capability[]) call.getRawArguments()[2];
            Set<Capability> permitted = actor == publisher || fullRead.get()
                    ? EnumSet.allOf(Capability.class)
                    : EnumSet.of(Capability.EXECUTE_REPOSITORY);
            if ((actor != publisher && revoked.get()) || !permitted.containsAll(Arrays.asList(needed))) {
                throw new AuthException(AuthException.Code.DENIED);
            }
            return new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, permitted);
        });
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(3)).get());
        publication = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        when(exports.createPublic(any(), any()))
                .thenAnswer(call -> publication.exports().createPublic(call.getArgument(0), call.getArgument(1)));
        when(exports.publicProjectionChanged(any(), any(), any()))
                .thenAnswer(call -> publication
                        .exports()
                        .publicProjectionChanged(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        doAnswer(call -> {
                    publication.exports().release(call.getArgument(0));
                    return null;
                })
                .when(exports)
                .release(any());
    }

    @AfterEach
    void closePublication() {
        if (publication != null) {
            publication.close();
        }
    }

    @Test
    void changedExplicitCopyIsPreservedAndNewUsesAFreshForkWithoutInspectingMutableGit() {
        try (NativeRepositoryExecutor executor = executor(2, (run, timeout, handler) -> {})) {
            String previousId = execute(executor, "new").copyId();
            AccountCopyRecord previous = journal.record(owner).orElseThrow();
            changeArticle("new public body");
            assertThatThrownBy(() -> execute(executor, previousId))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.PUBLICATION_CHANGED));
            assertThat(journal.record(owner)).contains(previous);
            RepositoryExecutor.ExecutionResult next = execute(executor, "new");
            assertThat(next.copyId()).isNotEqualTo(previousId);
            AccountCopyRecord.Owner fork = owner.forked(UUID.fromString(next.copyId()));
            AccountCopyRecord current = journal.record(fork).orElseThrow();
            assertThat(current.publicExport().projectionSha256())
                    .isNotEqualTo(previous.publicExport().projectionSha256());
            assertThat(journal.record(owner)).contains(previous);
            assertThat(volumes)
                    .containsKey(previous.writer().storage().volumeId().toString());
            assertThat(commands).noneMatch(source -> source.contains("git status") || source.contains("git diff"));
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void preservedStaleCopiesContinueToCountAgainstTheAccountQuota() {
        try (NativeRepositoryExecutor executor = executor(1, (run, timeout, handler) -> {})) {
            execute(executor, "new");
            AccountCopyRecord previous = journal.record(owner).orElseThrow();
            changeArticle("quota retains the old copy");
            assertThatThrownBy(() -> execute(executor, "new"))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.CAPACITY));
            assertThat(journal.record(owner)).contains(previous);
            assertThat(volumes).hasSize(1);
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void anUnrelatedPrivateCommitKeepsTheSamePublicRootAndScopeNeverExpands() {
        try (NativeRepositoryExecutor executor = executor(3, (run, timeout, handler) -> {})) {
            String publicId = execute(executor, "new").copyId();
            publication.competingWrite(auth, publisher);
            assertThat(execute(executor, publicId).freshSandbox()).isFalse();
            fullRead.set(true);
            assertThat(execute(executor, publicId).freshSandbox()).isFalse();
            assertThat(journal.record(owner).orElseThrow().owner().fullRead()).isFalse();
            String fullId = execute(executor, "new").copyId();
            assertThat(fullId).isNotEqualTo(publicId);
            fullRead.set(false);
            assertThatThrownBy(() -> execute(executor, fullId))
                    .isInstanceOfSatisfying(
                            ExecutionAdmissionException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ExecutionAdmissionException.Reason.MISSING_COPY));
            assertThat(execute(executor, publicId).copyId()).isEqualTo(publicId);
        }
    }

    @Test
    void withdrawnPublicationContainsAnIdleWarmRootWithoutAnotherCommand() throws Exception {
        try (NativeRepositoryExecutor executor = executor(2, (run, timeout, handler) -> {})) {
            execute(executor, "new");
            AccountCopyRecord previous = journal.record(owner).orElseThrow();
            publication.withdraw();
            time.addAndGet(Duration.ofSeconds(41).toNanos());
            awaitContainment(previous);
            assertThat(journal.record(owner)).contains(previous);
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ContentRepositoryException.class);
            assertThat(volumes).hasSize(1);
            verify(client, never()).deleteVolume(any());
        }
    }

    @Test
    void withdrawalDuringACommandContainsTheRootAndSuppressesItsOutput() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (NativeRepositoryExecutor executor = executor(2, (run, timeout, handler) -> {
                    entered.countDown();
                    await(release);
                });
                var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = requests.submit(() -> execute(executor, "new"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            AccountCopyRecord previous = journal.record(owner).orElseThrow();
            publication.withdraw();
            time.addAndGet(Duration.ofSeconds(41).toNanos());
            try {
                awaitContainment(previous);
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionUnconfirmedException.class);
            assertThat(journal.record(owner).orElseThrow().state()).isEqualTo(previous.state());
            assertThat(journal.record(owner).orElseThrow().writer().storage())
                    .isEqualTo(previous.writer().storage());
            verify(client, never()).deleteVolume(any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void revocationEventContainsABusyRootWithoutWaitingForAnotherRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (NativeRepositoryExecutor executor = executor(2, (run, timeout, handler) -> {
                    entered.countDown();
                    await(release);
                });
                var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = requests.submit(() -> execute(executor, "new"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            AccountCopyRecord previous = journal.record(owner).orElseThrow();
            revoked.set(true);
            executor.revoked(new AuthRevocation(workspace, Set.of(), Set.of(principal.subjectId())));
            assertThat(sessions.get(previous.writer().leaseId().toString()).terminal())
                    .isTrue();
            release.countDown();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ExecutionUnconfirmedException.class);
            assertThat(journal.record(owner).orElseThrow().writer().storage())
                    .isEqualTo(previous.writer().storage());
        } finally {
            release.countDown();
        }
    }

    @Test
    void retainedArtifactPagesRequireCurrentPublicationAndNeverEnlargeTheirScope() throws Exception {
        stdout = "public output".repeat(2000);
        try (NativeRepositoryExecutor executor = executor(3, (run, timeout, handler) -> {})) {
            RepositoryExecutor.ExecutionResult publicCopy = execute(executor, "new");
            String artifact = publicCopy.artifacts().get("stdout").artifactId();
            assertThat(executor.readArtifact(principal, workspace, artifact, 0, 16, cancellation))
                    .isPresent();
            fullRead.set(true);
            RepositoryExecutor.ExecutionResult fullCopy = execute(executor, "new");
            String privateArtifact = fullCopy.artifacts().get("stdout").artifactId();
            fullRead.set(false);
            assertThatThrownBy(() -> executor.readArtifact(principal, workspace, privateArtifact, 0, 16, cancellation))
                    .isInstanceOf(AuthException.class);
            publication.withdraw();
            assertThatThrownBy(() -> executor.readArtifact(principal, workspace, artifact, 16, 16, cancellation))
                    .isInstanceOf(ContentRepositoryException.class);
        }
    }

    @Test
    void eachMediaPageRechecksPublicationBeforeExposingItsResponse() {
        byte[] listing = """
                {"operation":"media_list","arguments":{"prefix":"","offset":0,"limit":100,"indexVersion":null,"commit":null}}
                """.getBytes(StandardCharsets.UTF_8);
        var firstPage = new AtomicBoolean();
        try (NativeRepositoryExecutor executor = executor(2, (run, timeout, handler) -> {
            byte[] response = handler.apply(listing);
            firstPage.set(
                    JsonMapper.builder().build().readTree(response).path("ok").booleanValue());
            changeArticle("publication changed between media pages");
            handler.apply(listing);
        })) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionUnconfirmedException.class);
            assertThat(firstPage).isTrue();
            assertThat(sessions.values()).allMatch(SandcrabProtocol.Session::terminal);
        }
    }

    @Override
    NativeRepositoryHost repositoryHost() {
        return new NativeRepositoryHost(
                saves,
                (lease, path, limit) -> new byte[0],
                mock(NativeArtifactCommands.class),
                new NativeMediaCommands(mock(NativeRepositoryFiles.class), publication.media(auth), saves, auth),
                mock(NativePlanCommands.class));
    }

    private NativeRepositoryExecutor executor(int copies, NativeRepositoryExecutor.CommandObserver observer) {
        return executor(observer, new ExecutionLimits(2, 2, Map.of(), Map.of(SiteGroup.COMMUNITY, copies)), time::get);
    }

    private void changeArticle(String source) {
        RepositoryFile current =
                publication.reader(auth).getFile(publisher, workspace, Optional.empty(), "public/article.md");
        publication
                .patches(auth)
                .apply(
                        publisher,
                        workspace,
                        new RepositoryPatch(
                                current.commit(),
                                List.of(new RepositoryTextChange(
                                        current.path(), false, current.revision(), Optional.of(source)))));
    }

    private void awaitContainment(AccountCopyRecord record) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!sessions.get(record.writer().leaseId().toString()).terminal() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(sessions.get(record.writer().leaseId().toString()).terminal())
                .isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Publication test did not release the command");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publication test was interrupted", failure);
        }
    }
}
