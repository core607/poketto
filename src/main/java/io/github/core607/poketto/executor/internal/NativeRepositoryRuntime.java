package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.nio.file.Path;
import java.time.Clock;

/** Composes the application entry directly with public file and event clients, without activating a second backend. */
final class NativeRepositoryRuntime {
    private NativeRepositoryRuntime() {}

    static NativeRepositoryExecutor create(
            SandcrabClient client,
            AuthService auth,
            AccountCopyStore accounts,
            RepositorySnapshotExports exports,
            SelectedFileSaves saves,
            MediaFileService media,
            PortableContentExports packages,
            ExecutionLimits limits,
            Path transfers,
            long transferBytes,
            String profile,
            long copyBytes,
            long copyInodes) {
        var storage = new NativeTransferStore(transfers, transferBytes, 4096);
        var files = new NativeRepositoryFiles(client, storage);
        var artifacts = new NativeArtifacts(storage, Clock.systemUTC());
        var host = new NativeRepositoryHost(
                saves,
                files,
                new NativeArtifactCommands(files, artifacts),
                new NativeMediaCommands(files, media, saves, auth),
                new NativePlanCommands(files, saves),
                new NativeExportCommands(files, packages));
        var workspaces =
                new NativeRepositoryWorkspace(client, auth, exports, saves, accounts, profile, copyBytes, copyInodes);
        return new NativeRepositoryExecutor(
                client,
                auth,
                accounts,
                workspaces,
                host,
                artifacts,
                (run, timeout, handler) ->
                        client.events().observeCalls(run, timeout, call -> handler.apply(call.bytes())),
                limits);
    }
}
