package io.github.core607.poketto.executor.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Snapshot bytes become caller-owned only after complete delivery, authorization and digest verification. */
final class SandcrabFiles {
    private static final long MAX_BYTES = 1_073_741_824;
    private final SandboxHttp http;

    SandcrabFiles(SandboxHttp http) {
        this.http = http;
    }

    record Plan(String path, String format, boolean freeze) {
        Plan {
            ProtocolValues.require(
                    "raw".equals(format) || "tar".equals(format), "snapshot format", "must be raw or tar");
            ProtocolValues.boundedText(path, 4096, "snapshot path");
            if (!(path.equals(".") && format.equals("tar"))) {
                ProtocolValues.require(
                        !path.contains("\\") && path.chars().noneMatch(Character::isISOControl),
                        "snapshot path",
                        "must be a relative workspace path");
                String[] parts = path.split("/", -1);
                ProtocolValues.inRange(parts.length, 1, 64, "snapshot path depth");
                for (String part : parts) {
                    ProtocolValues.boundedText(part, 255, "snapshot path component");
                    ProtocolValues.require(
                            !part.equals(".") && !part.equals(".."), "snapshot path", "must not traverse parents");
                }
            }
        }
    }

    record Content(byte[] bytes, String sha256) {
        Content {
            bytes = bytes.clone();
            sha256 = ProtocolValues.hex(sha256, 64, "snapshot digest");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record Snapshot(long bytes, String sha256) {}

    Content read(SandcrabProtocol.Session session, String path, int maxBytes, Runnable authorize) {
        ProtocolValues.inRange(maxBytes, 0, 8 * 1024 * 1024, "in-memory snapshot bound");
        var output = new ByteArrayOutputStream();
        Snapshot snapshot = capture(session, new Plan(path, "raw", true), maxBytes, authorize, output);
        return new Content(output.toByteArray(), snapshot.sha256());
    }

    /** The destination must be absent in a caller-owned directory; a failed transfer retains existing files. */
    Snapshot download(SandcrabProtocol.Session session, Plan plan, long maxBytes, Path target, Runnable authorize) {
        Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Snapshot destination already exists");
            }
            temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".sandcrab-", ".snapshot");
            Snapshot snapshot;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                snapshot = capture(session, plan, maxBytes, authorize, output);
            }
            authorize.run();
            Files.move(temporary, target);
            temporary = null;
            return snapshot;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    throw new WorkerUnavailableException(failure);
                }
            }
        }
    }

    private Snapshot capture(
            SandcrabProtocol.Session session, Plan plan, long maxBytes, Runnable authorize, OutputStream output) {
        ProtocolValues.inRange(maxBytes, 0, MAX_BYTES, "snapshot byte bound");
        authorize.run();
        return http.exchange(
                "POST",
                "/v1/sessions/" + session.id() + "/snapshot",
                "application/json",
                SandcrabProtocol.write(plan),
                Duration.ofSeconds(90),
                Duration.ZERO,
                (head, input) -> {
                    requireSuccess(head, input);
                    if (!session.serverId().equals(head.serverId())) {
                        throw new IOException("Native snapshot server epoch differs from its session");
                    }
                    if (head.length() > maxBytes || head.length() < 0) {
                        throw new IOException("Native snapshot exceeds the consumer's byte bound");
                    }
                    String expectedType =
                            plan.format().equals("raw") ? "application/octet-stream" : "application/x-tar";
                    if (!expectedType.equals(head.contentType())
                            || head.etag() == null
                            || !head.etag().matches("\"[0-9a-f]{64}\"")) {
                        throw new IOException("Native snapshot lacks its format or SHA-256 identity");
                    }
                    String expected = head.etag().substring(1, 65);
                    copy(input, output, head.length(), expected, authorize);
                    return new Snapshot(head.length(), expected);
                });
    }

    private static void copy(InputStream input, OutputStream output, long length, String expected, Runnable authorize)
            throws IOException {
        MessageDigest digest = SandcrabProtocol.digest();
        byte[] buffer = new byte[65536];
        long remaining = length;
        while (remaining > 0) {
            authorize.run();
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read <= 0) {
                throw new IOException("Native snapshot delivery was truncated");
            }
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw new IOException("Native snapshot digest differs from delivered bytes");
        }
        authorize.run();
    }

    private static void requireSuccess(SandboxHttp.Head head, InputStream input) throws IOException {
        if (head.status() == 200) {
            return;
        }
        if (!"application/json".equals(head.contentType()) || head.length() < 1 || head.length() > 65536) {
            throw new IOException("Invalid native snapshot refusal");
        }
        String code = SandcrabProtocol.read(input.readNBytes((int) head.length()), SandcrabProtocol.Failure.class)
                .error()
                .code();
        if (head.status() >= 400 && head.status() < 500) {
            throw new SandcrabClient.Rejected(head.status(), code);
        }
        throw new IOException("Native snapshot failed: " + code);
    }
}
