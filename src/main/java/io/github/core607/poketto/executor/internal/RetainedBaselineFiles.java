package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Indexed, independently compressed file frames. The caller owns private-directory validation,
 * cross-process exclusion, atomic publication, quota reservation and cleanup of unacknowledged files.
 */
final class RetainedBaselineFiles {
    private static final int MAX_ROW_BYTES = 8 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(MAX_ROW_BYTES)
                            .maxStringLength(1024 * 1024)
                            .maxNestingDepth(32)
                            .maxTokenCount(100_000)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private RetainedBaselineFiles() {}

    static RetainedBaseline.Reference write(
            Path temporary,
            RetainedBaseline.Identity identity,
            RetainedBaseline.Limits limits,
            Consumer<Consumer<RepositoryFile>> source)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                temporary,
                Set.of(CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var writer = new Writer(channel, identity, limits);
            source.accept(file -> {
                try {
                    writer.append(file);
                } catch (IOException failure) {
                    if (writer.failure == null) {
                        writer.failure = failure;
                    }
                    throw new UncheckedIOException(failure);
                }
            });
            return writer.finish();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    static Reader open(Path path, RetainedBaseline.Reference reference, RetainedBaseline.Limits limits)
            throws IOException {
        FileChannel channel = FileChannel.open(path, READ, NOFOLLOW_LINKS);
        boolean transferred = false;
        try {
            if (reference.bytes() > limits.archiveBytes() || channel.size() != reference.bytes()) {
                throw new IOException("retained baseline archive size differs");
            }
            if (!RetainedBaselineIo.digest(channel, reference.bytes()).equals(reference.sha256())) {
                throw new IOException("retained baseline archive checksum differs");
            }
            var header = RetainedBaselineHeader.read(channel, limits.archiveBytes(), JSON);
            if (!Objects.equals(header.identity(), reference.identity()) || header.entries() != reference.entries()) {
                throw new IOException("retained baseline archive identity differs");
            }
            var reader = new Reader(channel, header, limits);
            transferred = true;
            return reader;
        } finally {
            if (!transferred) {
                channel.close();
            }
        }
    }

    static RetainedBaseline.Identity identity(Path path, long maximum) throws IOException {
        try (FileChannel channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)) {
            return RetainedBaselineHeader.read(channel, maximum, JSON).identity();
        }
    }

    private static byte[] pathHash(String path) throws IOException {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        if (!path.equals(new String(bytes, StandardCharsets.UTF_8))) {
            throw new IOException("retained baseline path must be valid UTF-8");
        }
        return RetainedBaselineIo.sha256().digest(bytes);
    }

    private static void validateFile(RetainedBaseline.Identity identity, RepositoryFile file) throws IOException {
        if (!sameScope(identity, file)) {
            throw new IOException("retained baseline file does not match its source identity");
        }
        try {
            RepositoryPaths.validate(file.path());
            if (file.source().isPresent()) {
                String source = file.source().orElseThrow();
                new RetainedFileBaseline(identity.commit(), source);
                var revision = DocumentRevision.sha256(source.getBytes(StandardCharsets.UTF_8));
                if (!file.revision().equals(Optional.of(revision))) {
                    throw new IOException("retained baseline source revision differs");
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("retained baseline file is invalid", failure);
        }
    }

    private static boolean sameScope(RetainedBaseline.Identity identity, RepositoryFile file) {
        return file != null
                && !file.expectedAbsence()
                && file.path() != null
                && file.source() != null
                && file.revision() != null
                && Objects.equals(
                        file.workspaceId(), new WorkspaceId(identity.owner().workspaceId()))
                && Objects.equals(file.commit(), Optional.of(identity.commit()));
    }

    private record Entry(long offset, long compressed, long expanded) {}

    private static final class Writer {
        private final FileChannel channel;
        private final RetainedBaseline.Identity identity;
        private final RetainedBaseline.Limits limits;
        private final TreeMap<String, Entry> entries = new TreeMap<>();
        private final RetainedBaselineIo.Output archive;
        private long expanded;
        private IOException failure;

        private Writer(FileChannel channel, RetainedBaseline.Identity identity, RetainedBaseline.Limits limits)
                throws IOException {
            this.channel = channel;
            this.identity = Objects.requireNonNull(identity, "baseline identity must be present");
            this.limits = Objects.requireNonNull(limits, "baseline limits must be present");
            int header = RetainedBaselineHeader.size(identity, JSON);
            channel.position(header);
            archive = new RetainedBaselineIo.Output(Channels.newOutputStream(channel), limits.archiveBytes() - header);
        }

        private void append(RepositoryFile file) throws IOException {
            requireHealthy();
            validateFile(identity, file);
            if (entries.size() >= limits.entries()) {
                throw new RetainedBaselineIo.Limit();
            }
            String hash = HexFormat.of().formatHex(pathHash(file.path()));
            if (entries.containsKey(hash)) {
                throw new IOException("retained baseline path is duplicated");
            }
            long offset = channel.position();
            var compressed = new GZIPOutputStream(archive);
            var row = new RetainedBaselineIo.Output(
                    compressed, Math.min(MAX_ROW_BYTES, limits.expandedBytes() - expanded));
            try (compressed) {
                JSON.writeValue(row, file);
            } catch (JacksonException failure) {
                throw new IOException("retained baseline entry encoding failed", failure);
            }
            expanded += row.count();
            entries.put(hash, new Entry(offset, channel.position() - offset, row.count()));
        }

        private RetainedBaseline.Reference finish() throws IOException {
            requireHealthy();
            long offset = channel.position();
            long length = offset + (long) entries.size() * RetainedBaselineHeader.INDEX_BYTES;
            if (length > limits.archiveBytes()) {
                throw new RetainedBaselineIo.Limit();
            }
            var buffer = ByteBuffer.allocate(RetainedBaselineHeader.INDEX_BYTES);
            for (var entry : entries.entrySet()) {
                buffer.clear();
                buffer.put(HexFormat.of().parseHex(entry.getKey()));
                Entry value = entry.getValue();
                buffer.putLong(value.offset()).putLong(value.compressed()).putLong(value.expanded());
                RetainedBaselineIo.writeFully(channel, buffer.flip());
            }
            RetainedBaselineHeader.write(channel, identity, offset, entries.size(), JSON);
            channel.force(true);
            return new RetainedBaseline.Reference(
                    identity, RetainedBaselineIo.digest(channel, length), length, entries.size());
        }

        private void requireHealthy() throws IOException {
            if (failure != null) {
                throw new IOException("retained baseline writing did not complete", failure);
            }
        }
    }

    static final class Reader implements AutoCloseable {
        private final FileChannel channel;
        private final RetainedBaselineHeader header;
        private final ByteBuffer index;

        private Reader(FileChannel channel, RetainedBaselineHeader header, RetainedBaseline.Limits limits)
                throws IOException {
            this.channel = channel;
            this.header = header;
            if (header.entries() > limits.entries()) {
                throw new RetainedBaselineIo.Limit();
            }
            index = ByteBuffer.allocate(header.entries() * RetainedBaselineHeader.INDEX_BYTES);
            channel.position(header.indexOffset());
            RetainedBaselineIo.readFully(channel, index);
            validateIndex(limits.expandedBytes());
        }

        private void validateIndex(long maximumExpanded) throws IOException {
            byte[] previous = null;
            long expanded = 0;
            var frames = new Entry[header.entries()];
            for (int i = 0; i < header.entries(); i++) {
                byte[] hash = hashAt(i);
                if (previous != null && Arrays.compareUnsigned(previous, hash) >= 0) {
                    throw new IOException("retained baseline index is not strictly ordered");
                }
                Entry entry = entryAt(i);
                frames[i] = entry;
                if (!withinPayload(entry)) {
                    throw new IOException("retained baseline frame offset is invalid");
                }
                if (entry.expanded() < 1 || entry.expanded() > MAX_ROW_BYTES) {
                    throw new IOException("retained baseline frame size is invalid");
                }
                expanded += entry.expanded();
                if (expanded > maximumExpanded) {
                    throw new RetainedBaselineIo.Limit();
                }
                previous = hash;
            }
            Arrays.sort(frames, Comparator.comparingLong(Entry::offset));
            long offset = header.bytes();
            for (Entry frame : frames) {
                if (frame.offset() != offset) {
                    throw new IOException("retained baseline frames overlap or leave a gap");
                }
                offset += frame.compressed();
            }
            if (offset != header.indexOffset()) {
                throw new IOException("retained baseline payload length differs");
            }
        }

        private boolean withinPayload(Entry entry) {
            return entry.offset() >= header.bytes()
                    && entry.compressed() >= 1
                    && entry.compressed() <= header.indexOffset() - entry.offset();
        }

        synchronized Optional<RepositoryFile> find(String path) throws IOException {
            if (!channel.isOpen()) {
                throw new IOException("retained baseline reader is closed");
            }
            RepositoryPaths.validate(path);
            byte[] wanted = pathHash(path);
            int low = 0;
            int high = header.entries() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int compared = Arrays.compareUnsigned(hashAt(middle), wanted);
                if (compared == 0) {
                    return Optional.of(read(path, entryAt(middle)));
                }
                if (compared < 0) {
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return Optional.empty();
        }

        private RepositoryFile read(String path, Entry entry) throws IOException {
            channel.position(entry.offset());
            try (var gzip = new GZIPInputStream(new RetainedBaselineIo.FrameInput(channel, entry.compressed()))) {
                byte[] bytes = gzip.readNBytes((int) entry.expanded() + 1);
                if (bytes.length != entry.expanded() || gzip.read() != -1) {
                    throw new IOException("retained baseline expanded frame length differs");
                }
                RepositoryFile file = JSON.readValue(bytes, RepositoryFile.class);
                validateFile(header.identity(), file);
                if (!path.equals(file.path())) {
                    throw new IOException("retained baseline file address differs");
                }
                return file;
            } catch (JacksonException failure) {
                throw new IOException("retained baseline entry decoding failed", failure);
            }
        }

        private byte[] hashAt(int row) {
            byte[] hash = new byte[32];
            index.get(row * RetainedBaselineHeader.INDEX_BYTES, hash);
            return hash;
        }

        private Entry entryAt(int row) {
            int offset = row * RetainedBaselineHeader.INDEX_BYTES + 32;
            return new Entry(index.getLong(offset), index.getLong(offset + 8), index.getLong(offset + 16));
        }

        @Override
        public synchronized void close() throws IOException {
            channel.close();
        }
    }
}
