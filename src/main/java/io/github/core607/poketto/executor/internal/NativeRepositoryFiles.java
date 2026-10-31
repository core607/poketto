package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/** Bounded host staging and ordinary native transfers never overwrite an existing working file. */
final class NativeRepositoryFiles implements NativeRepositoryHost.SnapshotReader {
    private final NativeTransferStore storage;
    private final Snapshotter snapshots;

    NativeRepositoryFiles(SandcrabClient client, NativeTransferStore storage) {
        this(
                storage,
                (session, path, maximum, target, authorize) -> client.snapshot(
                        session, new SandcrabFiles.Plan(path, "raw", true), maximum, target, authorize));
    }

    NativeRepositoryFiles(NativeTransferStore storage, Snapshotter snapshots) {
        this.storage = storage;
        this.snapshots = snapshots;
    }

    NativeTransferStore.File capture(NativeRepositoryLease lease, String path, long maximum) {
        return captureNative(lease, "repository/" + RepositoryPaths.validate(path), maximum);
    }

    private NativeTransferStore.File captureNative(NativeRepositoryLease lease, String path, long maximum) {
        lease.authorize();
        return storage.capture(
                maximum,
                lease::authorize,
                target -> snapshots.capture(lease.session(), path, maximum, target, lease::authorize));
    }

    @Override
    public byte[] read(NativeRepositoryLease lease, String path, int maximum) {
        try (NativeTransferStore.File file = captureNative(lease, path, maximum);
                InputStream input = file.open()) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length != file.size()) {
                throw new IOException("Native text capture changed after verification");
            }
            lease.authorize();
            return bytes;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    Optional<byte[]> optional(NativeRepositoryLease lease, String path, int maximum) {
        try {
            return Optional.of(read(lease, "repository/" + RepositoryPaths.validate(path), maximum));
        } catch (SandcrabClient.Rejected missing) {
            if (GameSandboxJob.missing(missing, "FILE_NOT_FOUND")) {
                return Optional.empty();
            }
            throw missing;
        }
    }

    boolean install(
            NativeRepositoryLease lease, String path, long bytes, String sha256, NativeTransferStore.Source source) {
        RepositoryPaths.validate(path);
        try (NativeTransferStore.File file = storage.write(bytes, lease::authorize, source)) {
            if (file.size() != bytes || !file.sha256().equals(sha256)) {
                throw new IllegalArgumentException("Materialized original differs from its declared length or digest");
            }
            SandcrabProtocol.Upload uploaded = lease.upload("repository/" + path, file);
            if (uploaded.succeeded()) {
                return true;
            }
            if (uploaded.state().equals("failed") && "PATH_EXISTS".equals(uploaded.code())) {
                return identical(lease, path, file);
            }
            throw new WorkerUnavailableException();
        }
    }

    private boolean identical(NativeRepositoryLease lease, String path, NativeTransferStore.File expected) {
        try (NativeTransferStore.File actual = capture(lease, path, NativeArtifacts.MAX_FILE)) {
            return actual.size() == expected.size() && actual.sha256().equals(expected.sha256());
        } catch (SandcrabClient.Rejected refused) {
            if (refused.status() == 400 || refused.status() == 404 || refused.status() == 409) {
                return false;
            }
            throw refused;
        }
    }

    @FunctionalInterface
    interface Snapshotter {
        void capture(SandcrabProtocol.Session session, String path, long maximum, Path target, Runnable authorize);
    }
}
