package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LocalManagedBlobStoreTests {
    private static final String KEY = "synthetic-upload-01";

    @TempDir
    Path temp;

    @Test
    void streamsLargeOriginalsWithExactBytesAndDoesNotCloseCallerStreams() throws Exception {
        var workspace = WorkspaceId.random();
        var root = temp.resolve("originals");
        var store = ManagedBlobStore.local(root);
        long size = ManagedBlobStore.MAX_FILE_BYTES;
        var input = new InputStream() {
            long remaining = size;
            boolean closed;

            @Override
            public int read() {
                return remaining-- > 0 ? 42 : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) {
                    return -1;
                }
                int count = (int) Math.min(length, remaining);
                Arrays.fill(buffer, offset, offset + count, (byte) 42);
                remaining -= count;
                return count;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
        var asset = store.uploadFile(workspace, KEY, "video/mp4", input);
        assertThat(input.closed).isFalse();
        assertThat(asset.size()).isEqualTo(size);
        var output = new OutputStream() {
            long count;
            boolean closed;

            @Override
            public void write(int value) {
                assertThat(value).isEqualTo(42);
                count++;
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                for (int i = offset; i < offset + length; i++) {
                    if (bytes[i] != 42) {
                        throw new AssertionError("original bytes changed");
                    }
                }
                count += length;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
        assertThat(ManagedBlobStore.local(root).copyTo(workspace, asset.reference(), output))
                .isEqualTo(asset);
        assertThat(output.count).isEqualTo(size);
        assertThat(output.closed).isFalse();
        assertReason(AssetStorageException.Reason.TOO_LARGE, () -> store.read(workspace, asset.reference()));
    }

    @Test
    void deduplicatesPhysicalBytesOnlyWithinWorkspaceAndKeepsIndependentIdentities() throws Exception {
        var root = temp.resolve("originals");
        var store = ManagedBlobStore.local(root);
        var first = WorkspaceId.random();
        var second = WorkspaceId.random();
        byte[] bytes = "synthetic attachment".getBytes(StandardCharsets.UTF_8);
        var a = store.uploadFile(first, KEY, "application/pdf", new ByteArrayInputStream(bytes));
        var b = store.uploadFile(first, KEY + "-other", "application/octet-stream", new ByteArrayInputStream(bytes));
        var c = store.uploadFile(second, KEY, "application/pdf", new ByteArrayInputStream(bytes));
        Path aBytes = root.resolve(first.toString())
                .resolve("objects")
                .resolve(a.reference().assetId().toString())
                .resolve("bytes");
        Path bBytes = root.resolve(first.toString())
                .resolve("objects")
                .resolve(b.reference().assetId().toString())
                .resolve("bytes");
        Path cBytes = root.resolve(second.toString())
                .resolve("objects")
                .resolve(c.reference().assetId().toString())
                .resolve("bytes");
        assertThat(a.reference()).isNotEqualTo(b.reference()).isNotEqualTo(c.reference());
        assertThat(Files.isSameFile(aBytes, bBytes)).isTrue();
        assertThat(Files.isSameFile(aBytes, cBytes)).isFalse();
        assertReason(AssetStorageException.Reason.NOT_FOUND, () -> store.describe(second, a.reference()));
        assertReason(
                AssetStorageException.Reason.NOT_FOUND,
                () -> store.copyTo(second, a.reference(), new ByteArrayOutputStream()));
        assertThat(ManagedBlobStore.local(root)
                        .uploadFile(first, KEY, "application/pdf", new ByteArrayInputStream(bytes)))
                .isEqualTo(a);
        assertReason(
                AssetStorageException.Reason.IDEMPOTENCY_CONFLICT,
                () -> store.uploadFile(first, KEY, "application/octet-stream", new ByteArrayInputStream(bytes)));
        assertThat(store.list(first, 0, 100).items()).containsExactlyInAnyOrder(a, b);
        assertThat(store.list(second, 0, 100).items()).containsExactly(c);
    }

    @Test
    void declaredMediaTypeDoesNotMakeAnAttachmentSafeForImagePreview() {
        var store = ManagedBlobStore.local(temp.resolve("originals"));
        var workspace = WorkspaceId.random();
        byte[] bytes = "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8);
        for (String type : List.of("image/svg+xml", "image/png", "application/octet-stream")) {
            var asset = store.uploadFile(
                    workspace, KEY + type.replaceAll("[^a-z]", ""), type, new ByteArrayInputStream(bytes));
            var output = new ByteArrayOutputStream();
            store.copyTo(workspace, asset.reference(), output);
            assertThat(output.toByteArray()).isEqualTo(bytes);
            assertReason(AssetStorageException.Reason.INVALID_IMAGE, () -> store.read(workspace, asset.reference()));
        }
    }

    @Test
    void refusesCorruptOriginalBeforeWritingOutputAndRejectsCorruptDedupIndex() throws Exception {
        var root = temp.resolve("originals");
        var store = ManagedBlobStore.local(root);
        var workspace = WorkspaceId.random();
        byte[] bytes = "attachment".getBytes(StandardCharsets.UTF_8);
        var asset = store.uploadFile(workspace, KEY, "audio/mpeg", new ByteArrayInputStream(bytes));
        Path space = root.resolve(workspace.toString());
        Path original = space.resolve("objects")
                .resolve(asset.reference().assetId().toString())
                .resolve("bytes");
        Files.writeString(original, "corruption");
        var output = new ByteArrayOutputStream();
        assertReason(
                AssetStorageException.Reason.UNAVAILABLE, () -> store.copyTo(workspace, asset.reference(), output));
        assertThat(output.size()).isZero();
        Files.write(original, bytes);
        Files.writeString(space.resolve("digests").resolve(asset.reference().revision()), "corruption");
        assertReason(
                AssetStorageException.Reason.UNAVAILABLE,
                () -> store.uploadFile(workspace, KEY + "-second", "audio/mpeg", new ByteArrayInputStream(bytes)));
        assertThat(store.list(workspace, 0, 100).items()).containsExactly(asset);
    }

    @Test
    void uploadBoundReadsOnlyOneDetectionByteAndDoesNotRemoveEarlierOriginals() throws Exception {
        var root = temp.resolve("originals");
        var workspace = WorkspaceId.random();
        var store = ManagedBlobStore.local(root, 32);
        byte[] bytes = new byte[32];
        var original = store.uploadFile(workspace, KEY, "application/pdf", new ByteArrayInputStream(bytes));
        var input = new InputStream() {
            int reads;

            @Override
            public int read() {
                reads++;
                return 1;
            }
        };
        assertReason(
                AssetStorageException.Reason.TOO_LARGE,
                () -> store.uploadFile(workspace, KEY + "-large", "application/pdf", input));
        assertThat(input.reads).isEqualTo(33);
        assertThat(store.list(workspace, 0, 100).items()).containsExactly(original);
        try (var pending = Files.list(root.resolve(workspace.toString()).resolve("pending"))) {
            assertThat(pending.count()).isZero();
        }
        var output = new ByteArrayOutputStream();
        ManagedBlobStore.local(root, 1).copyTo(workspace, original.reference(), output);
        assertThat(output.toByteArray()).isEqualTo(bytes);
    }

    @Test
    void failedConsumerCanRetryWithoutChangingTheOriginal() {
        var store = ManagedBlobStore.local(temp.resolve("originals"));
        var workspace = WorkspaceId.random();
        byte[] bytes = "attachment".getBytes(StandardCharsets.UTF_8);
        var asset = store.uploadFile(workspace, KEY, "application/pdf", new ByteArrayInputStream(bytes));
        var failure = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("synthetic consumer failure");
            }
        };
        assertReason(
                AssetStorageException.Reason.UNAVAILABLE, () -> store.copyTo(workspace, asset.reference(), failure));
        var output = new ByteArrayOutputStream();
        store.copyTo(workspace, asset.reference(), output);
        assertThat(output.toByteArray()).isEqualTo(bytes);
    }

    @Test
    void maximumOriginalIsExactAndLaterTruncationOrGrowthIsRejected() throws Exception {
        var root = temp.resolve("originals");
        var workspace = WorkspaceId.random();
        var store = ManagedBlobStore.local(root);
        byte[] bytes = paddedPng(ManagedBlobStore.MAX_UPLOAD_BYTES);
        var asset = store.upload(workspace, KEY, new ByteArrayInputStream(bytes));
        assertThat(store.read(workspace, asset.reference()).bytes()).isEqualTo(bytes);
        Path original = root.resolve(workspace.toString())
                .resolve("objects")
                .resolve(asset.reference().assetId().toString())
                .resolve("bytes");
        try (var channel = FileChannel.open(original, StandardOpenOption.WRITE)) {
            channel.truncate(bytes.length - 1L);
        }
        assertReason(AssetStorageException.Reason.UNAVAILABLE, () -> store.read(workspace, asset.reference()));
        Files.write(original, bytes);
        Files.write(original, new byte[] {1}, StandardOpenOption.APPEND);
        assertReason(AssetStorageException.Reason.UNAVAILABLE, () -> store.read(workspace, asset.reference()));
    }

    @Test
    void preservesExactBytesAndReferenceAfterRestartForSupportedImageTypes() throws Exception {
        WorkspaceId workspace = WorkspaceId.random();
        ManagedBlobStore store = ManagedBlobStore.local(temp.resolve("originals"));
        for (String format : List.of("png", "jpg", "gif", "webp")) {
            byte[] original = image(format);
            var asset = store.upload(workspace, KEY + format, new ByteArrayInputStream(original));
            var restarted = ManagedBlobStore.local(temp.resolve("originals"));
            assertThat(restarted.upload(workspace, KEY + format, new ByteArrayInputStream(original)))
                    .isEqualTo(asset);
            assertThat(restarted.read(workspace, asset.reference()).bytes()).isEqualTo(original);
            assertThat(asset.reference().toString()).startsWith("managed:").doesNotContain(temp.toString());
        }
    }

    @Test
    void listsOnlyAcknowledgedUploadsWithStableBoundedPagination() throws Exception {
        Path root = temp.resolve("originals");
        var store = ManagedBlobStore.local(root);
        var workspace = WorkspaceId.random();
        var first = store.upload(workspace, KEY, new ByteArrayInputStream(image("png")));
        var second = store.upload(workspace, KEY + "-second", new ByteArrayInputStream(image("gif")));
        store.upload(workspace, KEY, new ByteArrayInputStream(image("png")));
        Files.createDirectory(root.resolve(workspace.toString())
                .resolve("objects")
                .resolve(UUID.randomUUID().toString()));
        var all = store.list(workspace, 0, 100);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.items()).containsExactlyInAnyOrder(first, second);
        assertThat(store.list(workspace, 1, 1).items())
                .containsExactly(all.items().get(1));
        assertThat(store.list(WorkspaceId.random(), 0, 100).items()).isEmpty();
        assertThatThrownBy(() -> store.list(workspace, 0, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAnImageLargerThanEightMiBWithoutChangingIt() throws Exception {
        byte[] png = image("png");
        byte[] payload = new byte[9 * 1024 * 1024];
        Arrays.fill(payload, (byte) 'x');
        payload[7] = 0;
        ByteArrayOutputStream large = new ByteArrayOutputStream();
        large.write(png, 0, png.length - 12);
        large.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        large.write(type);
        large.write(payload);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(payload);
        large.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        large.write(png, png.length - 12, 12);
        byte[] bytes = large.toByteArray();
        var workspace = WorkspaceId.random();
        var store = ManagedBlobStore.local(temp.resolve("originals"));
        var result = store.upload(workspace, KEY, new ByteArrayInputStream(bytes));
        assertThat(result.size()).isGreaterThan(8 * 1024 * 1024);
        assertThat(store.read(workspace, result.reference()).bytes()).isEqualTo(bytes);
    }

    @Test
    void scopesKeysAndReferencesToWorkspaceAndNeverOverwritesARevision() throws Exception {
        var store = ManagedBlobStore.local(temp.resolve("originals"));
        var first = WorkspaceId.random();
        var second = WorkspaceId.random();
        byte[] png = image("png");
        var original = store.upload(first, KEY, new ByteArrayInputStream(png));
        var independent = store.upload(second, KEY, new ByteArrayInputStream(png));
        assertThat(independent.reference().assetId())
                .isNotEqualTo(original.reference().assetId());
        assertReason(AssetStorageException.Reason.NOT_FOUND, () -> store.read(second, original.reference()));
        assertReason(
                AssetStorageException.Reason.IDEMPOTENCY_CONFLICT,
                () -> store.upload(first, KEY, new ByteArrayInputStream(image("gif"))));
        assertThat(store.read(first, original.reference()).bytes()).isEqualTo(png);
        assertReason(
                AssetStorageException.Reason.NOT_FOUND,
                () -> store.read(
                        first, new ManagedAssetReference(original.reference().assetId(), "0".repeat(64))));
    }

    @Test
    void concurrentStoreInstancesReturnOneAcknowledgedIdentity() throws Exception {
        var root = temp.resolve("originals");
        var workspace = WorkspaceId.random();
        byte[] png = image("png");
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = pool.invokeAll(IntStream.range(0, 12)
                    .<Callable<ManagedAssetReference>>mapToObj(i -> () -> ManagedBlobStore.local(root)
                            .upload(workspace, KEY, new ByteArrayInputStream(png))
                            .reference())
                    .toList());
            var identity = results.getFirst().get();
            for (var result : results) {
                assertThat(result.get()).isEqualTo(identity);
            }
        }
        try (var objects = Files.list(root.resolve(workspace.toString()).resolve("objects"))) {
            assertThat(objects.count()).isEqualTo(1);
        }
    }

    @Test
    void separateJvmUploadsSerializeThroughTheWorkspaceFileLock() throws Exception {
        Path root = temp.resolve("originals");
        Path input = Files.write(temp.resolve("image.png"), image("png"));
        Path held = temp.resolve("held");
        Path release = temp.resolve("release");
        String workspace = WorkspaceId.random().toString();
        String javaBinary =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of(ManagedUploadProcess.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + File.pathSeparator
                + Path.of(ManagedBlobStore.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
        var first = new ProcessBuilder(
                        javaBinary,
                        "-cp",
                        classpath,
                        ManagedUploadProcess.class.getName(),
                        root.toString(),
                        workspace,
                        input.toString(),
                        held.toString(),
                        release.toString())
                .redirectErrorStream(true)
                .start();
        Process second = null;
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(held) && System.nanoTime() < deadline && first.isAlive()) {
                Thread.sleep(10);
            }
            assertThat(Files.exists(held)).isTrue();
            second = new ProcessBuilder(
                            javaBinary,
                            "-cp",
                            classpath,
                            ManagedUploadProcess.class.getName(),
                            root.toString(),
                            workspace,
                            input.toString())
                    .redirectErrorStream(true)
                    .start();
            assertThat(second.waitFor(150, TimeUnit.MILLISECONDS)).isFalse();
            Files.createFile(release);
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String firstOutput = new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String secondOutput = new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(first.exitValue()).describedAs(firstOutput).isZero();
            assertThat(second.exitValue()).describedAs(secondOutput).isZero();
            assertThat(firstOutput).isEqualTo(secondOutput).startsWith("managed:");
        } finally {
            first.destroyForcibly();
            first.waitFor();
            if (second != null) {
                second.destroyForcibly();
                second.waitFor();
            }
        }
    }

    @Test
    void failedOrOversizedStreamsAcknowledgeNothingAndLeaveNoPendingFile() throws Exception {
        var root = temp.resolve("originals");
        var store = ManagedBlobStore.local(root);
        var workspace = WorkspaceId.random();
        InputStream failure = new InputStream() {
            int position;

            @Override
            public int read() throws IOException {
                if (position++ > 100) {
                    throw new IOException("synthetic failure");
                }
                return 1;
            }
        };
        assertReason(AssetStorageException.Reason.UNAVAILABLE, () -> store.upload(workspace, KEY, failure));
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 1;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 1);
                return length;
            }
        };
        assertReason(AssetStorageException.Reason.TOO_LARGE, () -> store.upload(workspace, KEY, endless));
        for (String folder : List.of("pending", "objects", "operations")) {
            try (var paths = Files.list(root.resolve(workspace.toString()).resolve(folder))) {
                assertThat(paths.count()).isZero();
            }
        }
        assertThat(store.upload(workspace, KEY, new ByteArrayInputStream(image("png"))))
                .isNotNull();
    }

    @Test
    void rejectsActiveContentTruncationAndOversizedDimensions() throws Exception {
        var store = ManagedBlobStore.local(temp.resolve("originals"));
        var workspace = WorkspaceId.random();
        for (byte[] bytes : List.of(
                "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xff, (byte) 0xd8},
                "GIF89a\u0000\u0000\u0000\u0000....;".getBytes(StandardCharsets.US_ASCII))) {
            assertReason(
                    AssetStorageException.Reason.INVALID_IMAGE,
                    () -> store.upload(workspace, KEY, new ByteArrayInputStream(bytes)));
        }
        byte[] tooWide = image("png");
        ByteBuffer.wrap(tooWide).putInt(16, 100_000);
        assertReason(
                AssetStorageException.Reason.INVALID_IMAGE,
                () -> store.upload(workspace, KEY, new ByteArrayInputStream(tooWide)));
    }

    @Test
    void rejectsWorkspaceObjectAndLedgerSymlinksWithoutTouchingTargets() throws Exception {
        var root = temp.resolve("originals");
        var outside = Files.createDirectory(temp.resolve("outside"));
        var sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged");
        var store = ManagedBlobStore.local(root);
        var linkedWorkspace = WorkspaceId.random();
        Files.createSymbolicLink(root.resolve(linkedWorkspace.toString()), outside);
        assertReason(
                AssetStorageException.Reason.UNAVAILABLE,
                () -> store.upload(linkedWorkspace, KEY, new ByteArrayInputStream(image("png"))));
        var workspace = WorkspaceId.random();
        var asset = store.upload(workspace, KEY, new ByteArrayInputStream(image("png")));
        Path object = root.resolve(workspace.toString())
                .resolve("objects")
                .resolve(asset.reference().assetId().toString());
        Files.delete(object.resolve("bytes"));
        Files.createSymbolicLink(object.resolve("bytes"), sentinel);
        assertReason(AssetStorageException.Reason.UNAVAILABLE, () -> store.read(workspace, asset.reference()));
        Path operations = root.resolve(workspace.toString()).resolve("operations");
        try (var files = Files.list(operations)) {
            Path ledger = files.findFirst().orElseThrow();
            Files.delete(ledger);
            Files.createSymbolicLink(ledger, sentinel);
        }
        assertReason(
                AssetStorageException.Reason.UNAVAILABLE,
                () -> store.upload(workspace, KEY, new ByteArrayInputStream(image("png"))));
        assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
    }

    @Test
    void survivesDeletionOfUnrelatedDerivedCacheAndRejectsObjectCorruption() throws Exception {
        var store = ManagedBlobStore.local(temp.resolve("originals"));
        var workspace = WorkspaceId.random();
        var result = store.upload(workspace, KEY, new ByteArrayInputStream(image("png")));
        var cache = Files.createDirectory(temp.resolve("derived-cache"));
        Files.writeString(cache.resolve("entry"), "disposable");
        Files.delete(cache.resolve("entry"));
        Files.delete(cache);
        assertThat(store.read(workspace, result.reference()).bytes()).isEqualTo(image("png"));
        Path bytes = temp.resolve("originals")
                .resolve(workspace.toString())
                .resolve("objects")
                .resolve(result.reference().assetId().toString())
                .resolve("bytes");
        Files.writeString(bytes, "corruption");
        assertReason(AssetStorageException.Reason.UNAVAILABLE, () -> store.read(workspace, result.reference()));
    }

    static byte[] image(String format) throws IOException {
        if (format.equals("webp")) {
            return Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out)) {
            throw new IOException("writer unavailable");
        }
        return out.toByteArray();
    }

    static byte[] paddedPng(int size) throws IOException {
        byte[] small = image("png");
        byte[] bytes = new byte[size];
        int offset = small.length - 12;
        int payload = size - small.length - 12;
        System.arraycopy(small, 0, bytes, 0, offset);
        ByteBuffer.wrap(bytes).putInt(offset, payload);
        System.arraycopy(new byte[] {'t', 'E', 'X', 't'}, 0, bytes, offset + 4, 4);
        Arrays.fill(bytes, offset + 8, offset + 8 + payload, (byte) 'x');
        bytes[offset + 8] = 'p';
        bytes[offset + 9] = 0;
        var crc = new CRC32();
        crc.update(bytes, offset + 4, payload + 4);
        ByteBuffer.wrap(bytes).putInt(offset + 8 + payload, (int) crc.getValue());
        System.arraycopy(small, small.length - 12, bytes, size - 12, 12);
        return bytes;
    }

    private static void assertReason(AssetStorageException.Reason reason, ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        AssetStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
