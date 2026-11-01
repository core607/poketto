package io.github.core607.poketto.executor.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Private parent/child protocol; every acknowledgement is bounded and belongs to one operator operation. */
final class NativeMigrationControl implements AutoCloseable {
    private static final int MAX_FRAME = 32 * 1024 * 1024;
    private static final byte[] PREFIX = "@poketto-migration:".getBytes(StandardCharsets.US_ASCII);
    private final String operation;
    private final InputStream input;
    private final OutputStream output;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "migration-control");
        thread.setDaemon(true);
        return thread;
    });

    NativeMigrationControl(String operation, InputStream input, OutputStream output) {
        this.operation = ProtocolValues.uuid(operation, "operator operation");
        this.input = new BufferedInputStream(input);
        this.output = output;
    }

    void send(String stage, AccountCopyRecord record, NativeRepositoryMigration.Plan plan) {
        byte[] bytes = SandcrabProtocol.write(new Notice(operation, stage, record, plan));
        ProtocolValues.require(bytes.length <= MAX_FRAME, "operator notice", "exceeds its bound");
        try {
            output.write(PREFIX);
            output.write(bytes);
            output.write('\n');
            output.flush();
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    Reply receive(String stage) {
        try {
            Reply reply = reader.submit(() -> SandcrabProtocol.read(readFrame(), Reply.class))
                    .get(stage.equals("archives") ? 300 : 30, TimeUnit.SECONDS);
            ProtocolValues.require(
                    operation.equals(reply.operation()) && stage.equals(reply.stage()),
                    "operator acknowledgement",
                    "must name this operation and stage");
            ProtocolValues.require(reply.proceed(), "operator acknowledgement", "did not confirm ownership");
            return reply;
        } catch (ExecutionException | TimeoutException failure) {
            throw new WorkerUnavailableException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        }
    }

    void fence(Lock source, Lock deployment) {
        source.requireHeld();
        deployment.requireHeld();
        send("fence", null, null);
        receive("fence");
        source.requireHeld();
        deployment.requireHeld();
    }

    static void requireInheritedLock(int descriptor, long device, long inode) {
        ProtocolValues.inRange(descriptor, 3, 1_048_576, "source lock descriptor");
        Path path = Path.of("/proc/self/fd/" + descriptor);
        try {
            ProtocolValues.require(
                    Files.getAttribute(path, "unix:dev").equals(device)
                            && Files.getAttribute(path, "unix:ino").equals(inode),
                    "source lock",
                    "must retain its inherited inode");
            String info = Files.readString(Path.of("/proc/self/fdinfo/" + descriptor));
            ProtocolValues.require(
                    info.lines()
                            .anyMatch(line ->
                                    line.startsWith("lock:") && line.contains(" FLOCK ") && line.contains(" WRITE ")),
                    "source lock",
                    "must retain an exclusive inherited FLOCK");
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    private byte[] readFrame() throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() <= MAX_FRAME) {
            int value = input.read();
            if (value == '\n') {
                return bytes.toByteArray();
            }
            if (value < 0) {
                throw new IOException("Operator controller disconnected");
            }
            bytes.write(value);
        }
        throw new IOException("Operator control frame exceeds its bound");
    }

    @Override
    public void close() {
        reader.shutdownNow();
    }

    record Notice(String operation, String stage, AccountCopyRecord record, NativeRepositoryMigration.Plan plan) {}

    record Archives(NativeRepositoryMigration.Archive work, NativeRepositoryMigration.Archive home) {}

    record Lock(int descriptor, long device, long inode) {
        void requireHeld() {
            requireInheritedLock(descriptor, device, inode);
        }
    }

    record Reply(
            String operation,
            String stage,
            boolean proceed,
            boolean execute,
            Archives archives,
            NativeRepositoryMigration.Plan plan) {}
}
