package io.github.core607.poketto.executor.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real Git/projection/export/transfer owners; the native file endpoint is a non-overwriting fixture. */
abstract class NativeExportFixture {
    @TempDir
    Path root;

    final AuthService auth = mock(AuthService.class);
    final AuthPrincipal actor = mock(AuthPrincipal.class);
    final AccountCommand held = mock(AccountCommand.class);
    final NativeRepositoryLease lease = mock(NativeRepositoryLease.class);
    final WorkspaceId workspace = WorkspaceId.random();
    final Map<String, byte[]> worktree = new ConcurrentHashMap<>();
    final AtomicBoolean denied = new AtomicBoolean();
    final AtomicBoolean expired = new AtomicBoolean();
    final AtomicInteger uploads = new AtomicInteger();
    final JsonMapper json = JsonMapper.builder().build();
    PublicExecutionNativeFixture fixture;
    NativeTransferStore storage;
    NativeRepositoryFiles files;
    PortableContentExports packages;
    NativeExportCommands exports;
    AccountCopyRecord record;

    @BeforeEach
    void configure() throws Exception {
        root = root.toRealPath();
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        when(auth.authorize(any(), any(), any(Capability[].class))).thenAnswer(call -> authorized());
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        fixture = new PublicExecutionNativeFixture(root, root.resolve("snapshots"), auth, workspace);
        packages = spy(fixture.packages(auth));
        storage = new NativeTransferStore(root.resolve("transfers"), 64L * 1024 * 1024, 16);
        files = new NativeRepositoryFiles(storage, (admission, path, maximum, target, authorize) -> {
            authorize.run();
            byte[] bytes = worktree.get(path.substring("work/repository/".length()));
            if (bytes == null) {
                throw new SandcrabClient.Rejected(404, "FILE_NOT_FOUND");
            }
            if (bytes.length > maximum) {
                throw new SandcrabClient.Rejected(400, "FILE_LIMIT");
            }
            try {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            } catch (IOException failure) {
                throw new WorkerUnavailableException(failure);
            }
        });
        scope(true);
        when(held.owner()).thenAnswer(call -> record.owner());
        when(held.record()).thenAnswer(call -> record);
        when(lease.remaining()).thenAnswer(call -> {
            if (expired.get()) {
                throw new WorkerUnavailableException(new IllegalStateException("Fixture command expired"));
            }
            return Duration.ofSeconds(30);
        });
        when(lease.transferLimit()).thenReturn(1024L * 1024 * 1024);
        when(lease.upload(anyString(), any())).thenAnswer(call -> install(call.getArgument(0), call.getArgument(1)));
        exports = new NativeExportCommands(files, packages);
    }

    WorkspaceAccess authorized() {
        if (denied.get()) {
            throw new AuthException(AuthException.Code.DENIED);
        }
        return new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, EnumSet.allOf(Capability.class));
    }

    void scope(boolean full) {
        RepositorySnapshotExports.PublicExport projection =
                full ? null : fixture.exports().createPublic(actor, workspace);
        var owner = new AccountCopyRecord.Owner(actor.accountId(), workspace.value(), full);
        UUID copy = UUID.randomUUID();
        var writer = new AccountCopyRecord.Writer(
                actor.subjectId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new AccountCopyRecord.NativeStorage(copy, "repository", 8_388_608, 256));
        String commit = full ? fixture.sourceCommit() : projection.export().commit();
        record = new AccountCopyRecord(
                owner,
                copy,
                1,
                System.currentTimeMillis() + 604_800_000,
                writer,
                AccountCopyRecord.Phase.RUNNING,
                UUID.randomUUID(),
                null,
                new SelectedFileSaves.State(commit).snapshot(),
                projection,
                full ? new AccountCopyRecord.Original("a".repeat(64), 64, 0) : null);
    }

    SandcrabProtocol.Upload install(String path, NativeTransferStore.File file) throws IOException {
        uploads.incrementAndGet();
        String relative = path.substring("work/repository/".length());
        boolean exists = worktree.putIfAbsent(relative, Files.readAllBytes(file.path())) != null;
        return new SandcrabProtocol.Upload(
                UUID.randomUUID().toString(),
                record.writer().workerBootId().toString(),
                record.writer().leaseId().toString(),
                "repository",
                record.copyId().toString(),
                path,
                "raw",
                file.size(),
                file.sha256(),
                "f".repeat(64),
                exists ? "failed" : "succeeded",
                exists ? "PATH_EXISTS" : null,
                exists ? "none" : "committed");
    }

    BridgeReplies.Reply command(List<String> paths, String output, boolean publicOnly) {
        return exports.command(actor, held, lease, arguments(paths, output, publicOnly));
    }

    JsonNode arguments(List<String> paths, String output, boolean publicOnly) {
        return json.readTree(SandcrabProtocol.write(new BridgeArguments.Export(paths, output, publicOnly)));
    }

    Map<String, String> zip(String path) throws IOException {
        var result = new LinkedHashMap<String, String>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(worktree.get(path)), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                result.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    @AfterEach
    void close() {
        if (storage != null) {
            storage.close();
        }
        if (fixture != null) {
            fixture.close();
        }
    }
}
