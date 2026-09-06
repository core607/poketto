package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Separate JVM fixture: the first process holds its OS lock until the test releases its stream. */
public final class ManagedUploadProcess {
    public static void main(String[] args) throws Exception {
        var store = ManagedBlobStore.local(Path.of(args[0]));
        try (var input = new FilterInputStream(Files.newInputStream(Path.of(args[2]))) {
            boolean held;

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (!held && args.length == 5) {
                    held = true;
                    Files.createFile(Path.of(args[3]));
                    long deadline = System.nanoTime() + 10_000_000_000L;
                    while (!Files.exists(Path.of(args[4]))) {
                        if (System.nanoTime() >= deadline) throw new IOException("synthetic release timed out");
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                    }
                }
                return super.read(bytes, offset, length);
            }
        }) {
            System.out.println(store.upload(WorkspaceId.parse(args[1]), "synthetic-upload-01", input)
                    .reference());
        }
    }
}
