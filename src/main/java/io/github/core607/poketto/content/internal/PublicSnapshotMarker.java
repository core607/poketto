package io.github.core607.poketto.content.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Directory durability is mandatory outside the explicit Windows online-only development mode. */
final class PublicSnapshotMarker {
    static final String NAME = "poketto-public-snapshot";
    private final boolean offlineRestoration;
    private final DirectorySync directories;

    PublicSnapshotMarker() {
        this(!System.getProperty("os.name").startsWith("Windows"), PublicSnapshotMarker::syncDirectory);
    }

    PublicSnapshotMarker(boolean offlineRestoration, DirectorySync directories) {
        this.offlineRestoration = offlineRestoration;
        this.directories = directories;
    }

    boolean supportsOfflineRestoration() {
        return offlineRestoration;
    }

    void write(Path gitDirectory, String commit, Instant verifiedAt, boolean open) throws IOException {
        Path marker = gitDirectory.resolve(NAME);
        Path temporary = gitDirectory.resolve(NAME + ".tmp");
        String prefix = offlineRestoration ? "DURABLE_V2" : "ONLINE_ONLY";
        String record = prefix + " " + commit + " " + verifiedAt + " ";
        try {
            Files.writeString(
                    temporary,
                    record + (open ? "OPEN" : "CLOSED") + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.SYNC);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (offlineRestoration) {
                // Persist the marker binding and a recreated cache's binding in its workspace.
                // Losing newly created higher ancestors can only lose the cache, never restore OPEN.
                directories.sync(gitDirectory);
                directories.sync(gitDirectory.getParent());
                directories.sync(gitDirectory.getParent().getParent());
            }
        } catch (IOException | UnsupportedOperationException failure) {
            if (open && Files.isRegularFile(marker)) {
                try {
                    // Invalidate the visible marker without another rename. When OPEN replaces a
                    // durable CLOSED, either surviving directory entry then describes CLOSED.
                    Files.writeString(
                            marker,
                            record + "CLOSED\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.SYNC);
                } catch (IOException | UnsupportedOperationException closingFailed) {
                    failure.addSuppressed(closingFailed);
                }
            }
            throw new WriteFailure(failure);
        }
    }

    static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface DirectorySync {
        void sync(Path directory) throws IOException;
    }

    static final class WriteFailure extends IOException {
        WriteFailure(Exception cause) {
            super("public snapshot marker could not be persisted", cause);
        }
    }
}
