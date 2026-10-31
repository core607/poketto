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
                (lease, path, maximum, target, authorize) -> client.snapshot(
                        lease.session(),
                        new SandcrabFiles.Plan(path, "raw", true),
                        maximum,
                        target,
                        lease.remaining(),
                        authorize));
    }

    NativeRepositoryFiles(NativeTransferStore storage, Snapshotter snapshots) {
        this.storage = storage;
        this.snapshots = snapshots;
    }

    NativeTransferStore.File capture(NativeRepositoryLease lease, String path, long maximum) {
        return captureNative(lease, NativeRepositoryLayout.repository(path), maximum);
    }

    private NativeTransferStore.File captureNative(NativeRepositoryLease lease, String path, long maximum) {
        authorize(lease);
        return storage.capture(
                maximum,
                () -> authorize(lease),
                target -> snapshots.capture(lease, path, maximum, target, () -> authorize(lease)));
    }

    @Override
    public byte[] read(NativeRepositoryLease lease, String path, int maximum) {
        try (NativeTransferStore.File file = captureNative(lease, path, maximum);
                InputStream input = file.open()) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length != file.size()) {
                throw new IOException("Native text capture changed after verification");
            }
            authorize(lease);
            return bytes;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    Optional<byte[]> optional(NativeRepositoryLease lease, String path, int maximum) {
        try {
            return Optional.of(read(lease, NativeRepositoryLayout.repository(path), maximum));
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
        try (NativeTransferStore.File file = storage.write(bytes, () -> authorize(lease), source)) {
            if (file.size() != bytes || !file.sha256().equals(sha256)) {
                throw new IllegalArgumentException("Materialized original differs from its declared length or digest");
            }
            SandcrabProtocol.Upload uploaded = lease.upload(NativeRepositoryLayout.repository(path), file);
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
        try (NativeTransferStore.File actual = capture(lease, path, expected.size())) {
            return actual.size() == expected.size() && actual.sha256().equals(expected.sha256());
        } catch (SandcrabClient.Rejected refused) {
            if (refused.status() == 400 || refused.status() == 404 || refused.status() == 409) {
                return false;
            }
            throw refused;
        }
    }

    private static void authorize(NativeRepositoryLease lease) {
        lease.authorize();
        lease.remaining();
    }

    @FunctionalInterface
    interface Snapshotter {
        void capture(NativeRepositoryLease lease, String path, long maximum, Path target, Runnable authorize);
    }
}
