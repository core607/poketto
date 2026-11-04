package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.RepositoryExecutor.ArtifactChunk;
import io.github.core607.poketto.mcp.RepositoryExecutor.ArtifactMetadata;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Ephemeral immutable results belong to an authorized account copy, independently of its current native shell. */
final class NativeArtifacts implements AutoCloseable {
    static final long MAX_FILE = 128L * 1024 * 1024;
    private static final long MAX_COPY_BYTES = 256L * 1024 * 1024;
    private static final int PREVIEW_BYTES = 16384;
    private static final long LIFETIME = Duration.ofMinutes(5).toMillis();
    private final NativeTransferStore storage;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    NativeArtifacts(NativeTransferStore storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    synchronized ArtifactMetadata retain(
            AccountCopyRecord record, String name, String mediaType, boolean truncated, NativeTransferStore.File file) {
        collect();
        long count = entries.values().stream()
                .filter(entry -> entry.copyId().equals(record.copyId()))
                .count();
        long bytes = entries.values().stream()
                .filter(entry -> entry.copyId().equals(record.copyId()))
                .mapToLong(entry -> entry.file().size())
                .sum();
        if (count >= 16 || file.size() > MAX_FILE || file.size() > MAX_COPY_BYTES - bytes) {
            throw new NativeTransferStore.Capacity();
        }
        String id = UUID.randomUUID().toString();
        var metadata = new ArtifactMetadata(id, name, mediaType, file.size(), file.sha256(), truncated, 300);
        var entry = new Entry(
                record.owner(),
                record.copyId(),
                record.executionId(),
                record.publicExport() == null ? null : record.publicExport().projectionSha256(),
                metadata,
                file,
                Math.addExact(clock.millis(), LIFETIME));
        entries.put(id, entry);
        return metadata;
    }

    Output output(AccountCopyRecord record, SandcrabProtocol.Result result, Runnable authorize) {
        Map<String, ArtifactMetadata> artifacts = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        String stdout = result.stdout() == null ? "" : result.stdout();
        String stderr = result.stderr() == null ? "" : result.stderr();
        boolean truncated = Boolean.TRUE.equals(result.truncated());
        retainOutput(record, "stdout", stdout, truncated, authorize, artifacts, errors);
        retainOutput(record, "stderr", stderr, truncated, authorize, artifacts, errors);
        return new Output(
                preview(stdout),
                preview(stderr),
                truncated || bytes(stdout) > PREVIEW_BYTES,
                truncated || bytes(stderr) > PREVIEW_BYTES,
                Map.copyOf(artifacts),
                Map.copyOf(errors));
    }

    private void retainOutput(
            AccountCopyRecord record,
            String stream,
            String text,
            boolean truncated,
            Runnable authorize,
            Map<String, ArtifactMetadata> artifacts,
            Map<String, String> errors) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= PREVIEW_BYTES) {
            return;
        }
        NativeTransferStore.File file = null;
        boolean retained = false;
        try {
            file = storage.write(bytes.length, authorize, output -> output.write(bytes));
            artifacts.put(stream, retain(record, stream + ".txt", "text/plain", truncated, file));
            retained = true;
        } catch (NativeTransferStore.Capacity capacity) {
            errors.put(stream, "ARTIFACT_CAPACITY");
        } finally {
            if (!retained && file != null) {
                file.close();
            }
        }
    }

    synchronized Optional<AccountCopyRecord.Owner> owner(AuthPrincipal principal, WorkspaceId workspace, String id) {
        ProtocolValues.uuid(id, "artifact ID");
        collect();
        Entry entry = entries.get(id);
        return entry != null
                        && entry.owner().accountId().equals(principal.accountId())
                        && entry.owner().workspaceId().equals(workspace.value())
                ? Optional.of(entry.owner())
                : Optional.empty();
    }

    synchronized Optional<ArtifactChunk> read(
            AccountCopyRecord current, String id, long offset, int limit, Runnable authorize) {
        ProtocolValues.uuid(id, "artifact ID");
        ProtocolValues.inRange(offset, 0, MAX_FILE, "artifact offset");
        ProtocolValues.inRange(limit, 1, 65536, "artifact page bytes");
        collect();
        Entry entry = entries.get(id);
        if (entry == null || !matches(entry, current)) {
            return Optional.empty();
        }
        if (offset > entry.file().size()) {
            throw new IllegalArgumentException("Artifact offset exceeds the retained file");
        }
        authorize.run();
        byte[] bytes = page(entry.file(), offset, limit);
        authorize.run();
        int remaining = (int) Math.min(300, (entry.expiresAt() - clock.millis() + 999) / 1000);
        if (remaining < 1) {
            remove(entry);
            return Optional.empty();
        }
        ArtifactMetadata metadata = entry.metadata();
        return Optional.of(new ArtifactChunk(
                id,
                metadata.name(),
                metadata.mediaType(),
                metadata.bytes(),
                metadata.sha256(),
                metadata.truncated(),
                remaining,
                offset,
                bytes));
    }

    private static byte[] page(NativeTransferStore.File file, long offset, int limit) {
        byte[] bytes = new byte[(int) Math.min(limit, file.size() - offset)];
        try (FileChannel input = FileChannel.open(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (input.size() != file.size()) {
                throw new IOException("Retained artifact length changed");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = input.read(buffer, offset + buffer.position());
                if (read < 1) {
                    throw new IOException("Retained artifact ended before its metadata");
                }
            }
            return bytes;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    private static boolean matches(Entry entry, AccountCopyRecord current) {
        if (current == null
                || !entry.owner().equals(current.owner())
                || !entry.copyId().equals(current.copyId())) {
            return false;
        }
        String projection =
                current.publicExport() == null ? null : current.publicExport().projectionSha256();
        return Objects.equals(entry.projection(), projection);
    }

    synchronized boolean remove(AccountCopyRecord current, String id) {
        Entry entry = entries.get(ProtocolValues.uuid(id, "artifact ID"));
        if (entry == null || !matches(entry, current)) {
            return false;
        }
        remove(entry);
        return true;
    }

    synchronized void removeCopy(UUID copy) {
        removeIf(entry -> entry.copyId().equals(copy));
    }

    synchronized void removeExecution(UUID copy, UUID execution) {
        removeIf(entry -> entry.copyId().equals(copy) && Objects.equals(entry.execution(), execution));
    }

    synchronized void removeAccounts(WorkspaceId workspace, Set<UUID> accounts) {
        removeIf(entry -> entry.owner().workspaceId().equals(workspace.value())
                && accounts.contains(entry.owner().accountId()));
    }

    synchronized void collect() {
        removeIf(entry -> entry.expiresAt() <= clock.millis());
        storage.collect();
    }

    private void removeIf(Predicate<Entry> selected) {
        for (Entry entry : List.copyOf(entries.values())) {
            if (selected.test(entry)) {
                remove(entry);
            }
        }
    }

    private void remove(Entry entry) {
        entries.remove(entry.metadata().artifactId(), entry);
        entry.file().close();
    }

    @Override
    public synchronized void close() {
        removeIf(entry -> true);
        storage.close();
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String preview(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= PREVIEW_BYTES) {
            return text;
        }
        int end = PREVIEW_BYTES;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private record Entry(
            AccountCopyRecord.Owner owner,
            UUID copyId,
            UUID execution,
            String projection,
            ArtifactMetadata metadata,
            NativeTransferStore.File file,
            long expiresAt) {}

    record Output(
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Map<String, ArtifactMetadata> artifacts,
            Map<String, String> errors) {}
}
