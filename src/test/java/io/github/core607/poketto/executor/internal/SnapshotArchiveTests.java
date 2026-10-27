package io.github.core607.poketto.executor.internal;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The archive layout is the one sandcrab-core's snapshot writer pins on its own side. */
class SnapshotArchiveTests {
    private static byte[] header(String name, long size) {
        byte[] block = new byte[512];
        System.arraycopy(name.getBytes(US_ASCII), 0, block, 0, name.length());
        System.arraycopy("0000644".getBytes(US_ASCII), 0, block, 100, 7);
        System.arraycopy("0000000".getBytes(US_ASCII), 0, block, 108, 7);
        System.arraycopy("0000000".getBytes(US_ASCII), 0, block, 116, 7);
        System.arraycopy(String.format("%011o", size).getBytes(US_ASCII), 0, block, 124, 11);
        System.arraycopy("00000000000".getBytes(US_ASCII), 0, block, 136, 11);
        block[156] = '0';
        System.arraycopy("ustar\0".getBytes(US_ASCII), 0, block, 257, 6);
        System.arraycopy("00".getBytes(US_ASCII), 0, block, 263, 2);
        for (int index = 148; index < 156; index++) {
            block[index] = ' ';
        }
        int sum = 0;
        for (byte value : block) {
            sum += value & 255;
        }
        System.arraycopy((String.format("%06o", sum) + "\0").getBytes(US_ASCII), 0, block, 148, 7);
        return block;
    }

    private static void entry(ByteArrayOutputStream out, String name, byte[] content) {
        out.writeBytes(header(name, content.length));
        out.writeBytes(content);
        out.writeBytes(new byte[(512 - content.length % 512) % 512]);
    }

    private static byte[] archive(String manifest, byte[]... files) {
        var out = new ByteArrayOutputStream();
        entry(out, "manifest.json", manifest.getBytes(UTF_8));
        for (int index = 0; index < files.length; index++) {
            entry(out, "files/" + index, files[index]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    @Test
    void readsTheManifestThenEachFileInOrder() throws IOException {
        var archive = new SnapshotArchive(
                new ByteArrayInputStream(archive("{\"ok\":true}", "abc".getBytes(UTF_8), new byte[0])));
        assertThat(archive.manifest(JsonMapper.builder().build()).path("ok").booleanValue())
                .isTrue();
        assertThat(archive.file(3).readAllBytes()).isEqualTo("abc".getBytes(UTF_8));
        assertThat(archive.file(0).readAllBytes()).isEmpty();
    }

    @Test
    void refusesDamagedOrMisorderedArchives() throws IOException {
        byte[] good = archive("{\"ok\":true}", "abc".getBytes(UTF_8));
        var json = JsonMapper.builder().build();

        var wrongSize = new SnapshotArchive(new ByteArrayInputStream(good));
        wrongSize.manifest(json);
        assertThatThrownBy(() -> wrongSize.file(4)).isInstanceOf(IOException.class);

        byte[] corrupt = good.clone();
        corrupt[0] = 'M';
        assertThatThrownBy(() -> new SnapshotArchive(new ByteArrayInputStream(corrupt)).manifest(json))
                .isInstanceOf(IOException.class);

        byte[] checksum = good.clone();
        checksum[1024 + 124 + 10] = '4';
        var damaged = new SnapshotArchive(new ByteArrayInputStream(checksum));
        damaged.manifest(json);
        assertThatThrownBy(() -> damaged.file(4)).isInstanceOf(IOException.class);

        var unread = new SnapshotArchive(new ByteArrayInputStream(archive("{}", "abc".getBytes(UTF_8), new byte[1])));
        unread.manifest(json);
        unread.file(3);
        assertThatThrownBy(() -> unread.file(1)).isInstanceOf(IOException.class);

        byte[] truncated = java.util.Arrays.copyOf(good, 1024 + 512 + 1);
        var cut = new SnapshotArchive(new ByteArrayInputStream(truncated));
        cut.manifest(json);
        assertThatThrownBy(() -> cut.file(3).readAllBytes()).isInstanceOf(IOException.class);
    }
}
