package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryContentReaderTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void adoptsNestedChineseMarkdownWithoutChangingOriginalText() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        byte[] original = bytes("\ufeff# 雨夜\r\n\r\n原样保存。\r\n");
        var commit = fixture.commitRemote(workspace, Map.of("城市/雨.md", original, "plain.md", bytes("A note.")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var tree = reader.readTree(workspace, Optional.empty());
        assertThat(tree.commit()).contains(commit.name());
        assertThat(tree.documents()).hasSize(2);
        var document = tree.documents().get(1);
        assertThat(document.title()).isEqualTo("雨夜");
        assertThat(document.route()).isEqualTo("/城市/雨");
        assertThat(document.createdAt()).isEqualTo(Instant.parse("2026-09-01T09:00:00Z"));
        assertThat(document.file().source().orElseThrow().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(original);
        assertThat(document.file().revision()).contains(DocumentRevision.sha256(original));
        assertThat(tree.documents().getFirst().title()).isEqualTo("plain");
    }

    @Test
    void malformedFilesAreDiagnosedWhileUnrelatedDocumentsRemainReadable() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(
                workspace,
                Map.of(
                        "good.md",
                        bytes("# Good"),
                        "bad.md",
                        bytes("---\ntitle: [\n---\ntext"),
                        "binary.md",
                        new byte[] {(byte) 0xff},
                        "unsafe.md",
                        bytes("---\nroute: /../private\n---\n# Wrong")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var tree = reader.readTree(workspace, Optional.empty());
        assertThat(tree.documents())
                .extracting(document -> document.file().path())
                .containsExactly("good.md");
        assertThat(tree.diagnostics())
                .extracting(RepositoryDiagnostic::path)
                .contains("bad.md", "binary.md", "unsafe.md");
        var bad = reader.getFile(workspace, tree.commit(), "bad.md");
        assertThat(bad.source()).contains("---\ntitle: [\n---\ntext");
        assertThat(bad.revision()).isPresent();
        assertThat(bad.expectedAbsence()).isFalse();
        assertThat(bad.diagnostics()).extracting(RepositoryDiagnostic::code).containsExactly("INVALID_MARKDOWN");
    }

    @Test
    void exactCommitReadsIgnoreLaterCommitsAndWorktreeEdits() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var first = fixture.commitRemote(workspace, Map.of("note.md", bytes("# Before")));
        var second = fixture.commitRemote(workspace, Map.of("note.md", bytes("# After")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        assertThat(reader.getFile(workspace, Optional.empty(), "note.md").commit())
                .contains(second.name());
        Files.writeString(fixture.cache(workspace).resolve("note.md"), "# Mutated cache");
        var file = reader.getFile(workspace, Optional.of(first.name()), "note.md");
        assertThat(file.source()).contains("# Before");
        assertThat(file.commit()).contains(first.name());
        assertThatThrownBy(() -> reader.getFile(workspace, Optional.of("HEAD~1"), "note.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.getFile(workspace, Optional.of("0".repeat(40)), "note.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingPathsReturnExpectedAbsenceAndOtherWorkspacesDoNotShareObjects() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(workspace, Map.of("note.md", bytes("# Here")));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var absent = reader.getFile(workspace, Optional.of(commit.name()), "missing.md");
        assertThat(absent.expectedAbsence()).isTrue();
        assertThat(absent.source()).isEmpty();
        assertThat(absent.revision()).isEmpty();
        assertThatThrownBy(() -> reader.getFile(WorkspaceId.random(), Optional.of(commit.name()), "note.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void folderRouteCollisionsQuarantineEveryClaimantAndPrivateRemainsReadable() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(
                workspace,
                Map.of(
                        "notes.md",
                        bytes("# Note"),
                        "notes/index.md",
                        bytes("# Folder"),
                        "private/secret.md",
                        bytes("# Secret"),
                        "nested/private/open.md",
                        bytes("# Open"),
                        ".poketto/internal.md",
                        bytes("# Hidden")));
        var tree = new JGitRepositoryContentReader(fixture.authority()).readTree(workspace, Optional.empty());
        assertThat(tree.documents())
                .extracting(document -> document.file().path())
                .containsExactly("nested/private/open.md", "private/secret.md");
        assertThat(tree.documents().getFirst().privatePath()).isFalse();
        assertThat(tree.documents().getLast().privatePath()).isTrue();
        assertThat(tree.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.code().equals("ROUTE_COLLISION")))
                .extracting(RepositoryDiagnostic::path)
                .containsExactly("notes.md", "notes/index.md");
    }

    @Test
    void optionalMetadataOverridesFallbacksButUnknownMetadataIsPreserved() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        String source =
                "---\ntitle: Authored\ndate: 2026-08-20\nupdated_at: 2026-09-02T10:00:00+08:00\ntags: [知识, Git]\ncustom: keep me\nroute: /chosen\n---\n# Heading\n";
        fixture.commitRemote(workspace, Map.of("note.md", bytes(source)));
        var tree = new JGitRepositoryContentReader(fixture.authority()).readTree(workspace, Optional.empty());
        assertThat(tree.diagnostics()).isEmpty();
        var document = tree.documents().getFirst();
        assertThat(document.title()).isEqualTo("Authored");
        assertThat(document.createdAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
        assertThat(document.updatedAt()).isEqualTo(Instant.parse("2026-09-02T02:00:00Z"));
        assertThat(document.tags()).containsExactly("知识", "Git");
        assertThat(document.route()).isEqualTo("/chosen");
        assertThat(document.file().source()).contains(source);
        assertThat(document.body()).isEqualTo("# Heading\n");
    }

    @Test
    void oversizedFileDoesNotPreventReadingOtherFiles() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("large.md", new byte[1024 * 1024 + 1]);
        files.put("small.md", bytes("# Small"));
        fixture.commitRemote(workspace, files);
        var tree = new JGitRepositoryContentReader(fixture.authority()).readTree(workspace, Optional.empty());
        assertThat(tree.documents()).hasSize(1);
        assertThat(tree.diagnostics()).extracting(RepositoryDiagnostic::code).contains("FILE_TOO_LARGE");
    }

    @Test
    void inferredDatesFollowChangesToThatFileRatherThanTheCurrentCommit() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var first = Instant.parse("2026-08-01T09:00:00Z");
        var changed = Instant.parse("2026-08-02T09:00:00Z");
        fixture.commitRemote(workspace, Map.of("note.md", bytes("# First")), Map.of(), first);
        fixture.commitRemote(workspace, Map.of("note.md", bytes("# Changed")), Map.of(), changed);
        fixture.commitRemote(
                workspace,
                Map.of("note.md", bytes("# Changed"), "other.md", bytes("# New")),
                Map.of(),
                changed.plusSeconds(86400));
        var tree = new JGitRepositoryContentReader(fixture.authority()).readTree(workspace, Optional.empty());
        var note = tree.documents().stream()
                .filter(document -> document.file().path().equals("note.md"))
                .findFirst()
                .orElseThrow();
        assertThat(note.createdAt()).isEqualTo(first);
        assertThat(note.updatedAt()).isEqualTo(changed);
    }

    @Test
    void objectReadsDoNotMaterializeImagesOrApplyLegacyDocumentBounds() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var commit = fixture.commitRemote(
                workspace,
                Map.of(
                        "documents/large.md",
                        new byte[1024 * 1024 + 1],
                        "documents/bad.md",
                        bytes("---\nnot: [valid\n---\n"),
                        "good.md",
                        bytes("# Good"),
                        "picture.png",
                        new byte[2 * 1024 * 1024]));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var tree = reader.readTree(workspace, Optional.empty());
        assertThat(tree.documents())
                .extracting(document -> document.file().path())
                .containsExactly("good.md");
        assertThat(tree.diagnostics())
                .extracting(RepositoryDiagnostic::path)
                .contains("documents/large.md", "documents/bad.md");
        assertThat(fixture.cache(workspace).resolve("picture.png")).doesNotExist();
        assertThat(fixture.cache(workspace).resolve("documents")).doesNotExist();
        assertThat(fixture.remoteHead(workspace)).isEqualTo(commit);
        assertThat(reader.getFile(workspace, Optional.of(commit.name()), "good.md")
                        .source())
                .contains("# Good");
    }

    @Test
    void linksAndCaseFoldCollisionsNeverBecomeStructuredDocuments() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(
                workspace,
                Map.of(
                        "link.md",
                        bytes("private/secret.md"),
                        "private/secret.md",
                        bytes("# Private"),
                        "A.md",
                        bytes("# A"),
                        "a.md",
                        bytes("# a")),
                Map.of("link.md", org.eclipse.jgit.lib.FileMode.SYMLINK));
        var reader = new JGitRepositoryContentReader(fixture.authority());
        var tree = reader.readTree(workspace, Optional.empty());
        assertThat(tree.documents())
                .extracting(document -> document.file().path())
                .containsExactly("private/secret.md");
        assertThat(tree.diagnostics())
                .extracting(RepositoryDiagnostic::code)
                .contains("NOT_REGULAR_FILE", "PATH_COLLISION");
        var link = reader.getFile(workspace, Optional.empty(), "link.md");
        assertThat(link.expectedAbsence()).isFalse();
        assertThat(link.source()).isEmpty();
        assertThat(link.revision()).isEmpty();
    }

    @Test
    void headingFallbackSkipsFencedCodeAndYamlAndSupportsSetext() {
        var parser = new RepositoryMarkdownParser();
        assertThat(parser.parse("note.md", "---\ncustom: '# YAML'\n---\n```md\n# Code\n```\nReal title\n===\n")
                        .title())
                .isEqualTo("Real title");
        assertThatThrownBy(() -> parser.parse("note.md", "---\ntitle: one\ntitle: two\n---\nBody"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("index.md", "---\nroute: /elsewhere\n---\n# Folder"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsafePathsAreRejectedBeforeRepositoryAccess() {
        assertThat(RepositoryPathRules.reserved("nested/.POKETTO/internal.md")).isTrue();
        var reader = new JGitRepositoryContentReader(new RemoteRepositoryFixture(directory).authority());
        for (String path : new String[] {
            "../secret.md", "/secret.md", "a//b.md", "a/../../b.md", ".git/config", "C:/secret.md", "a\\b.md"
        }) {
            assertThatThrownBy(() -> reader.getFile(workspace, Optional.empty(), path))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
