package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class AccountFileBaselinesTests {
    private static final String KEY = "c".repeat(64);
    private static final UUID COPY = UUID.randomUUID();
    private static final Map<String, RetainedFileBaseline> FIRST =
            Map.of("public/猫.md", RetainedFileBaseline.saved("a".repeat(40), "# 猫咪\n"));
    private static final Map<String, RetainedFileBaseline> SECOND = Map.of(
            "public/猫.md", RetainedFileBaseline.saved("b".repeat(40), "# 猫咪\n第二版\n"),
            "private/草稿.md", RetainedFileBaseline.saved("b".repeat(40), null));

    @TempDir
    Path parent;

    @Test
    void storesVersionsUnderTheirDigestOnceAndReadsThemWithoutTheCache() throws Exception {
        Path root = parent.resolve("metadata");
        String digest = baselines(root).publish(KEY, COPY, FIRST);

        assertThat(baselines(root).publish(KEY, COPY, FIRST)).isEqualTo(digest);
        assertThat(versionFiles(root)).containsExactly(KEY + ".acknowledged-" + digest);
        assertThat(Files.getPosixFilePermissions(root.resolve(KEY + ".acknowledged-" + digest)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(baselines(root).read(KEY, COPY, digest)).isEqualTo(FIRST);
    }

    @Test
    void keepsOnlyTheReferencedVersionAndNeverServesAnotherCopy() throws Exception {
        Path root = parent.resolve("metadata");
        var store = baselines(root);
        String first = store.publish(KEY, COPY, FIRST);
        String second = store.publish(KEY, COPY, SECOND);

        store.retainOnly(KEY, second);

        assertThat(versionFiles(root)).containsExactly(KEY + ".acknowledged-" + second);
        assertThat(store.read(KEY, COPY, second)).isEqualTo(SECOND);
        assertThatThrownBy(() -> baselines(root).read(KEY, COPY, first)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.read(KEY, UUID.randomUUID(), second))
                .isInstanceOf(IllegalArgumentException.class);
        store.retainOnly(KEY, null);
        assertThat(versionFiles(root)).isEmpty();
    }

    @Test
    void refusesBytesThatDifferFromTheDigestInTheirName() throws Exception {
        Path root = parent.resolve("metadata");
        var store = baselines(root);
        String first = store.publish(KEY, COPY, FIRST);
        String second = store.publish(KEY, COPY, SECOND);
        Files.move(
                root.resolve(KEY + ".acknowledged-" + second),
                root.resolve(KEY + ".acknowledged-" + first),
                StandardCopyOption.REPLACE_EXISTING);

        assertThatThrownBy(() -> baselines(root).read(KEY, COPY, first))
                .isInstanceOf(IOException.class)
                .hasMessage("retained record differs from its referenced digest");
    }

    private static AccountFileBaselines baselines(Path root) throws IOException {
        var files = new RetainedRecordFiles(1024 * 1024);
        return new AccountFileBaselines(root, new RetainedDirectory(root), files, 1024 * 1024);
    }

    private static List<String> versionFiles(Path root) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".acknowledged-"))
                    .sorted()
                    .toList();
        }
    }
}
