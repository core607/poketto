package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.*;
import io.github.core607.poketto.auth.*;
import io.github.core607.poketto.content.*;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PortableContentPlannerTests {
    @TempDir
    Path root;

    final WorkspaceId workspace = WorkspaceId.random();
    final AuthPrincipal actor = mock(AuthPrincipal.class);
    final AuthService auth = mock(AuthService.class);

    private PortableContentPlanner planner(
            RemoteRepositoryFixture fixture, PublicContentSnapshots snapshots, ManagedBlobStore store) {
        return new PortableContentPlanner(
                auth,
                new JGitRepositoryContentReader(fixture.authority()),
                new JGitRepositoryBlobReader(fixture.authority()),
                snapshots,
                () -> store);
    }

    private Map<String, byte[]> archive(PortableContentPlanner.Plan plan) throws Exception {
        Path file = root.resolve(UUID.randomUUID() + ".zip");
        try (var output = Files.newOutputStream(file)) {
            PortableArchiveWriter.write(
                    output,
                    plan.entries(),
                    new PortableArchiveWriter.Limits(100, 1048576, 2097152, Duration.ofSeconds(5)),
                    plan.authorize());
        }
        var result = new TreeMap<String, byte[]>();
        try (var zip = new ZipFile(file.toFile())) {
            for (var entry : zip.stream().toList()) {
                try (var input = zip.getInputStream(entry)) {
                    result.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return result;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void privatePackageContainsMixedOriginalsAndRepairedLinksWithoutIndexOrHistory() throws Exception {
        var fixture = new RemoteRepositoryFixture(root.resolve("git"));
        var store = ManagedBlobStore.local(root.resolve("originals").toAbsolutePath());
        var index = new TreeMap<String, RepositoryMediaIndex.Media>();
        var expected = new ArrayList<byte[]>();
        String[] names = {"image.png", "audio.mp3", "video.mp4", "manual.pdf", "binary.bin"};
        String[] types = {"image/png", "audio/mpeg", "video/mp4", "application/pdf", "application/octet-stream"};
        var body = new StringBuilder("---\nprivate_note: keep-this\n---\n# Note\n[Other](other.md)\n");
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = new byte[] {(byte) i, 0, -1, 2};
            expected.add(bytes);
            var asset =
                    store.uploadFile(workspace, "export_original_000" + i, types[i], new ByteArrayInputStream(bytes));
            index.put(
                    "private/files/" + names[i],
                    new RepositoryMediaIndex.Media(
                            asset.reference().assetId(),
                            asset.reference().revision(),
                            asset.mediaType(),
                            asset.size()));
            body.append("[Download](files/").append(names[i]).append(")\n");
        }
        fixture.commitRemote(
                workspace,
                Map.of(
                        "private/note.md",
                        text(body.toString()),
                        "private/other.md",
                        text("# Other\n"),
                        RepositoryMediaIndex.PATH,
                        new RepositoryMediaIndex(index).encode(),
                        "AGENTS.md",
                        text("private runtime guidance")));
        var plan = planner(fixture, mock(PublicContentSnapshots.class), store)
                .prepare(actor, workspace, List.of("private"), false);
        var contents = archive(plan);
        assertThat(contents).hasSize(7);
        assertThat(contents.keySet())
                .noneMatch(path -> path.contains(".git") || path.contains(".poketto") || path.contains("AGENTS.md"));
        String note = new String(contents.get("content/private/note.md"), StandardCharsets.UTF_8);
        assertThat(note).contains("private_note: keep-this", "[Other](other.md)");
        assertThat(MarkdownDestinations.parse(note).links()).allSatisfy(link -> {
            String path =
                    MarkdownDestinations.path("content/private/note.md", link).orElseThrow();
            assertThat(contents).containsKey(path);
        });
        for (byte[] bytes : expected)
            assertThat(contents.values())
                    .anySatisfy(actual -> assertThat(actual).containsExactly(bytes));
    }

    @Test
    void publicPackageDropsPrivateMetadataAndHtmlAndRevalidatesGitImageEligibility() throws Exception {
        var fixture = new RemoteRepositoryFixture(root.resolve("git"));
        var files = new LinkedHashMap<String, byte[]>();
        files.put(RepositoryPublishingPolicy.PATH, text("enabled: true\nmode: public-by-default\n"));
        files.put(
                "article.md",
                text(
                        "---\ntitle: Public\nprivate_note: secret-metadata\n---\n# Public\n![pic](images/pic.png)\n[unsafe](javascript:alert)\n<script>secret-html</script>\n"));
        files.put("images/pic.png", new byte[] {0, -1, 2});
        files.put("private/hidden.md", text("# Secret\nprivate-body\n"));
        fixture.commitRemote(workspace, files);
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        var service = planner(fixture, snapshots, mock(ManagedBlobStore.class));
        var plan = service.prepare(actor, workspace, List.of("article.md"), true);
        var contents = archive(plan);
        assertThat(contents).hasSize(2);
        String article = new String(contents.get("content/article-1.md"), StandardCharsets.UTF_8);
        assertThat(article)
                .contains("title: \"Public\"", "../media/original-1.png")
                .doesNotContain("secret-metadata", "secret-html", "javascript:", "private-body", "article.md");
        assertThat(contents.get("media/original-1.png")).containsExactly(0, -1, 2);
        assertThatThrownBy(() -> service.prepare(actor, workspace, List.of("private/hidden.md"), true))
                .isInstanceOf(ContentRepositoryException.class);
        files.put("private/hidden.md", text("# Secret\nchanged private body\n"));
        fixture.commitRemote(workspace, files);
        snapshots.refresh(workspace);
        plan.authorize().run();
        files.put(
                RepositoryPublishingPolicy.PATH,
                text("enabled: true\nmode: public-by-default\nexclude:\n  - images/**\n"));
        fixture.commitRemote(workspace, files);
        snapshots.refresh(workspace);
        assertThatThrownBy(plan.authorize()::run).isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void indexedIdentityCannotBorrowAnotherWorkspacesOriginal() throws Exception {
        var fixture = new RemoteRepositoryFixture(root.resolve("git"));
        var store = ManagedBlobStore.local(root.resolve("originals").toAbsolutePath());
        var asset = store.uploadFile(
                workspace, "export_cross_workspace_001", "application/pdf", new ByteArrayInputStream(new byte[] {1, 2
                }));
        WorkspaceId other = WorkspaceId.random();
        fixture.commitRemote(
                other,
                Map.of(
                        "private/note.md",
                        text("[PDF](paper.pdf)\n"),
                        RepositoryMediaIndex.PATH,
                        new RepositoryMediaIndex(Map.of(
                                        "private/paper.pdf",
                                        new RepositoryMediaIndex.Media(
                                                asset.reference().assetId(),
                                                asset.reference().revision(),
                                                asset.mediaType(),
                                                asset.size())))
                                .encode()));
        assertThatThrownBy(() -> planner(fixture, mock(PublicContentSnapshots.class), store)
                        .prepare(actor, other, List.of("private/note.md"), false))
                .isInstanceOf(AssetStorageException.class);
    }

    @Test
    void absentDependenciesAndInternalSelectionsDoNotProduceAPlan() throws Exception {
        var fixture = new RemoteRepositoryFixture(root.resolve("git"));
        fixture.commitRemote(
                workspace, Map.of("private/note.md", text("![missing](missing.png)\n"), "AGENTS.md", text("guide\n")));
        var service = planner(fixture, mock(PublicContentSnapshots.class), mock(ManagedBlobStore.class));
        assertThatThrownBy(() -> service.prepare(actor, workspace, List.of("private/note.md"), false))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> service.prepare(actor, workspace, List.of("AGENTS.md"), false))
                .isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void readDenialPrecedesRepositoryAndOriginalAccess() {
        var reader = mock(RepositoryContentReader.class);
        var blobs = mock(RepositoryBlobReader.class);
        var snapshots = mock(PublicContentSnapshots.class);
        var store = mock(ManagedBlobStore.class);
        doThrow(new SecurityException("denied")).when(auth).authorize(actor, workspace, Capability.READ_PRIVATE);
        var service = new PortableContentPlanner(auth, reader, blobs, snapshots, () -> store);
        assertThatThrownBy(() -> service.prepare(actor, workspace, List.of("private"), false))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(reader, blobs, snapshots, store);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
