package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Set;

/** Fixture requests name fixed actions; only the root controller chooses and owns native units. */
final class NativeGameControl {
    private static final Set<String> ACTIONS =
            Set.of("game-assert-clean", "game-await-running", "game-restart-worker", "game-repository-overlap");
    private final Path directory;
    private final String token;
    private int sequence;

    NativeGameControl(Path directory, String token) {
        this.directory = directory;
        this.token = ProtocolValues.uuid(token, "fixture identity");
    }

    void run(String action) throws Exception {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unknown native game fixture action");
        }
        var request = new Request(token, ++sequence, action);
        publish("request-" + sequence, request);
        Path ack = directory.resolve("ack-" + sequence + ".json");
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ack, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(ack) > 4096
                        || !request.equals(SandcrabProtocol.read(Files.readAllBytes(ack), Request.class))) {
                    throw new IllegalStateException("Native game fixture acknowledgement changed identity");
                }
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Native game controller did not acknowledge its action");
    }

    void complete(Object result) throws IOException {
        if (Files.exists(directory.resolve("result.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException("Native game result already exists");
        }
        Files.write(directory.resolve("result.started"), new byte[0], StandardOpenOption.CREATE_NEW);
        publish("result", result);
    }

    private void publish(String name, Object value) throws IOException {
        byte[] bytes = SandcrabProtocol.write(value);
        if (bytes.length > 65536) {
            throw new IllegalArgumentException("Native game fixture publication exceeds its bound");
        }
        Path pending = directory.resolve(name + ".pending");
        Files.write(pending, bytes, StandardOpenOption.CREATE_NEW);
        Files.move(pending, directory.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE);
    }

    record Request(String token, int sequence, String action) {}
}
