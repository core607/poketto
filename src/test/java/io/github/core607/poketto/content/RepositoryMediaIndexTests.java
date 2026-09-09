package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepositoryMediaIndexTests {
    private static final RepositoryMediaIndex.Media MEDIA = new RepositoryMediaIndex.Media(
            UUID.fromString("fe746b86-e14e-4b5d-82cb-8f03655e4ef8"), "a".repeat(64), "application/pdf", 1024);

    @Test
    void roundTripsLogicalPathsWithoutChangingSharedReferencesOrUnicode() {
        var entries = new LinkedHashMap<String, RepositoryMediaIndex.Media>();
        entries.put("private/资料/原件.pdf", MEDIA);
        entries.put("public/文章/插图.pdf", MEDIA);
        var index = new RepositoryMediaIndex(entries);
        entries.clear();
        assertThat(RepositoryMediaIndex.parse(index.encode())).isEqualTo(index);
        assertThat(index.files()).hasSize(2);
        assertThatThrownBy(() -> index.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(RepositoryMediaIndex.parse(RepositoryMediaIndex.empty().encode())
                        .files())
                .isEmpty();
    }

    @Test
    void rejectsUnsafeReservedOverlappingAndNormalizedCollidingPaths() {
        for (String path : List.of(
                "/absolute.pdf",
                "../escape.pdf",
                "private//a.pdf",
                "private\\a.pdf",
                ".git/a.pdf",
                "private/.PoKeTTo/a.pdf",
                "private/notes.md",
                "private/x\u0000.pdf")) {
            assertThatThrownBy(() -> new RepositoryMediaIndex(Map.of(path, MEDIA)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String[] paths : List.of(
                new String[] {"private/A.pdf", "private/a.pdf"},
                new String[] {"private/café.pdf", "private/cafe\u0301.pdf"},
                new String[] {"private/a", "private/a/b.pdf"})) {
            assertThatThrownBy(() -> new RepositoryMediaIndex(Map.of(paths[0], MEDIA, paths[1], MEDIA)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsJsonAmbiguityCoercionUnknownFieldsAndMalformedUtf8() {
        String valid =
                new String(new RepositoryMediaIndex(Map.of("private/a.pdf", MEDIA)).encode(), StandardCharsets.UTF_8);
        for (String invalid : List.of(
                valid + "{}",
                valid.replace("\"version\" : 1", "\"version\" : 1, \"version\" : 1"),
                valid.replace("\"version\" : 1", "\"version\" : 4294967297"),
                valid.replace("\"size\" : 1024", "\"size\" : \"1024\""),
                valid.replace("\"size\" : 1024", "\"size\" : 1.5"),
                valid.replace("\"size\" : 1024", "\"size\" : 1024, \"public\" : true"),
                valid.replace(MEDIA.assetId().toString(), "fe746b86-e14e-4b5d-82cb-8f03655e4ef8".toUpperCase()),
                valid.replace("application/pdf", "text/html; charset=utf-8"))) {
            assertThat(invalid).isNotEqualTo(valid);
            assertThatThrownBy(() -> RepositoryMediaIndex.parse(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid or oversized repository media index");
        }
        assertThatThrownBy(() -> RepositoryMediaIndex.parse(new byte[] {(byte) 0xff}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryMediaIndex.parse(new byte[RepositoryMediaIndex.MAX_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preventsLogicalFilesFromMaskingGitEntriesOrTraversingGitSymlinks() {
        var index = new RepositoryMediaIndex(Map.of("private/album/photo.pdf", MEDIA));
        for (String path : List.of("private/album", "PRIVATE/ALBUM/PHOTO.PDF", "private/album/photo.pdf/child")) {
            assertThatThrownBy(() -> index.requireNoGitCollisions(List.of(path)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        index.requireNoGitCollisions(List.of("private/album/note.md", RepositoryMediaIndex.PATH));
    }
}
