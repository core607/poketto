package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositoryBaselineLimits;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real Git reconciliation with generic file transport and journal peers; durable blob tests run separately on Linux. */
abstract class NativePlanFixture {
    @TempDir
    Path root;

    final AuthService auth = mock(AuthService.class);
    final AuthPrincipal actor = mock(AuthPrincipal.class);
    final AccountCommand held = mock(AccountCommand.class);
    final AccountCopyRecord record = mock(AccountCopyRecord.class);
    final NativeRepositoryLease lease = mock(NativeRepositoryLease.class);
    final NativeRepositoryFiles files = mock(NativeRepositoryFiles.class);
    final WorkspaceId workspace = WorkspaceId.random();
    final UUID account = UUID.randomUUID();
    final Map<String, byte[]> local = new HashMap<>();
    final Map<String, byte[]> retained = new HashMap<>();
    final List<RetainedSaveState> journals = new ArrayList<>();
    final AtomicReference<RetainedSaveState> state = new AtomicReference<>();
    final AtomicBoolean denied = new AtomicBoolean();
    final AtomicBoolean loseMaterialization = new AtomicBoolean();
    PublicExecutionNativeFixture repository;
    NativeTransferStore transfers;
    SelectedFileSaves saves;
    NativePlanCommands commands;
    NativeRepositoryHost host;

    @BeforeEach
    void initialize() throws Exception {
        root = root.toRealPath();
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.accountId()).thenReturn(account);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(auth.authorize(any(), any(), any(Capability[].class))).thenAnswer(call -> {
            authorize();
            return new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, EnumSet.allOf(Capability.class));
        });
        when(auth.withAuthorization(any(), any(), any(), any())).thenAnswer(call -> {
            authorize();
            return ((Supplier<?>) call.getArgument(3)).get();
        });
        doAnswer(call -> {
                    authorize();
                    return null;
                })
                .when(lease)
                .authorize();
        repository = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        saves = new SelectedFileSaves(auth, repository.reader(auth), repository.patches(auth), repository.moves(auth));
        state.set(new SelectedFileSaves.State(repository.sourceCommit()).snapshot());
        var owner = new AccountCopyRecord.Owner(account, workspace.value(), true);
        when(held.owner()).thenReturn(owner);
        when(record.owner()).thenReturn(owner);
        when(record.copyId()).thenReturn(UUID.randomUUID());
        when(held.record()).thenReturn(record);
        when(record.state()).thenAnswer(call -> state.get());
        when(held.state())
                .thenAnswer(call -> SelectedFileSaves.State.restore(state.get(), next -> {
                    state.set(next);
                    journals.add(next);
                    if (next.nativePlan() == null) {
                        retained.clear();
                    }
                }));
        saves.visitOriginal(
                actor,
                workspace,
                repository.sourceCommit(),
                new RepositoryBaselineLimits(100, 1024 * 1024, Duration.ofSeconds(5)),
                file -> file.source()
                        .ifPresent(source -> local.put(file.path(), source.getBytes(StandardCharsets.UTF_8))));
        transfers = new NativeTransferStore(root.resolve("transfers"), 16 * 1024 * 1024, 64);
        storagePeers();
        commands = new NativePlanCommands(files, saves);
        host = new NativeRepositoryHost(
                saves, files, mock(NativeArtifactCommands.class), mock(NativeMediaCommands.class), commands);
    }

    private void storagePeers() throws Exception {
        when(files.capture(any(), anyString(), anyLong())).thenAnswer(call -> {
            authorize();
            byte[] bytes = local.get((String) call.getArgument(1));
            if (bytes == null) {
                throw new SandcrabClient.Rejected(404, "FILE_NOT_FOUND");
            }
            return transfers.write(bytes.length, this::authorize, output -> output.write(bytes));
        });
        doAnswer(call -> {
                    UUID plan = call.getArgument(0);
                    NativePlanFile file = call.getArgument(1);
                    assertThat(state.get().nativePlan().id()).isEqualTo(plan);
                    var output = new ByteArrayOutputStream();
                    ((NativeTransferStore.Source) call.getArgument(2)).write(output);
                    ((Runnable) call.getArgument(3)).run();
                    assertThat(output.size()).isEqualTo(file.bytes());
                    assertThat(SandcrabProtocol.sha256(output.toByteArray())).isEqualTo(file.sha256());
                    retained.putIfAbsent(file.sha256(), output.toByteArray());
                    return null;
                })
                .when(held)
                .publishPlanFile(any(), any(), any(), any());
        doAnswer(call -> {
                    NativePlanFile file = call.getArgument(1);
                    ((Runnable) call.getArgument(3)).run();
                    ((OutputStream) call.getArgument(2)).write(retained.get(file.sha256()));
                    return null;
                })
                .when(held)
                .copyPlanFile(any(), any(), any(), any());
        when(files.install(any(), anyString(), anyLong(), anyString(), any())).thenAnswer(call -> {
            String path = call.getArgument(1);
            var output = new ByteArrayOutputStream();
            ((NativeTransferStore.Source) call.getArgument(4)).write(output);
            byte[] proposed = output.toByteArray();
            assertThat(proposed.length).isEqualTo((long) call.getArgument(2));
            assertThat(SandcrabProtocol.sha256(proposed)).isEqualTo((String) call.getArgument(3));
            byte[] previous = local.putIfAbsent(path, proposed);
            if (loseMaterialization.compareAndSet(true, false)) {
                throw new WorkerUnavailableException();
            }
            return previous == null || Arrays.equals(previous, proposed);
        });
    }

    JsonNode call(String operation, Object arguments) {
        return call(operation, arguments, () -> false);
    }

    JsonNode call(String operation, Object arguments, BooleanSupplier outOfTime) {
        JsonMapper json = JsonMapper.builder().build();
        when(lease.outOfTime(any())).thenAnswer(call -> outOfTime.getAsBoolean());
        byte[] request =
                SandcrabProtocol.write(new NativeRepositoryHost.Request(operation, json.valueToTree(arguments)));
        return json.readTree(host.reply(actor, held, lease, request));
    }

    String changeRemote(String path, String source) {
        RepositoryFile current = repository.reader(auth).getFile(actor, workspace, Optional.empty(), path);
        return repository
                .patches(auth)
                .apply(
                        actor,
                        workspace,
                        new RepositoryPatch(
                                current.commit(),
                                List.of(new RepositoryTextChange(
                                        path,
                                        current.expectedAbsence(),
                                        current.revision(),
                                        Optional.ofNullable(source)))))
                .commit();
    }

    void authorize() {
        if (denied.get()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
    }

    @AfterEach
    void close() {
        if (transfers != null) {
            transfers.close();
        }
        if (repository != null) {
            repository.close();
        }
    }
}
