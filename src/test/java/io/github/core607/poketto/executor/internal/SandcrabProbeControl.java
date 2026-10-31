package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;

/** Fixture-only rendezvous: Java requests a named fault; the root controller owns its exact unit. */
final class SandcrabProbeControl {
    private final Path directory;
    private int sequence;

    SandcrabProbeControl(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        require(Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS), "Fixture control directory is absent");
    }

    Request signal(String action, SandcrabProtocol.Reference run) {
        var request = new Request(++sequence, action, run);
        Path pending = directory.resolve("request-" + sequence + ".pending");
        Path published = directory.resolve("request-" + sequence + ".json");
        try {
            Files.write(pending, SandcrabProtocol.write(request), StandardOpenOption.CREATE_NEW);
            Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE);
            return request;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not publish the fixture fault request", failure);
        }
    }

    Ack await(Request request) {
        Path path = directory.resolve("ack-" + request.sequence() + ".json");
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.size(path) <= 4096, "Fixture acknowledgement exceeded its bound");
                    Ack ack = SandcrabProtocol.read(Files.readAllBytes(path), Ack.class);
                    require(ack.sequence() == request.sequence(), "Fixture acknowledgement changed its sequence");
                    require(ack.action().equals(request.action()), "Fixture acknowledgement changed its action");
                    require(
                            ack.previousServerId().equals(request.run().serverId()),
                            "Fixture acknowledgement changed its original daemon epoch");
                    return ack;
                }
                Thread.sleep(20);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read the fixture acknowledgement", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture rendezvous was interrupted", interrupted);
        }
        throw new IllegalStateException("Fixture controller did not acknowledge its fault within the bound");
    }

    void effect(String phase) {
        try {
            Files.writeString(directory.resolve(phase + ".effect"), "effect-once\n", StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new IllegalStateException("Synthetic host effect was repeated or could not be recorded", failure);
        }
    }

    void verifyEffect(String phase) {
        try {
            require(
                    Files.readString(directory.resolve(phase + ".effect")).equals("effect-once\n"),
                    "Synthetic host effect changed across daemon restart");
        } catch (IOException failure) {
            throw new IllegalStateException("Synthetic host effect could not be reconciled", failure);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

    record Request(int sequence, String action, SandcrabProtocol.Reference run) {
        Request {
            ProtocolValues.inRange(sequence, 1, 5, "fixture request sequence");
            ProtocolValues.boundedText(action, 64, "fixture action");
            Objects.requireNonNull(run, "fixture run reference must be present");
        }
    }

    record Ack(
            int sequence,
            String action,
            String previousServerId,
            String serverId,
            @JsonProperty(required = true) boolean containmentConfirmed) {
        Ack {
            ProtocolValues.inRange(sequence, 1, 5, "fixture acknowledgement sequence");
            ProtocolValues.boundedText(action, 64, "fixture acknowledgement action");
            previousServerId = ProtocolValues.uuid(previousServerId, "fixture original epoch");
            serverId = ProtocolValues.uuid(serverId, "fixture observed epoch");
        }
    }
}
