package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositorySnapshotExportsTests {
    @TempDir
    Path directory;

    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final AuthService auth = mock(AuthService.class);

    @Test
    void publicValidityIgnoresPrivateCommitsButRejectsChangedPublicationAndOtherWorkspaces() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var tree = new java.util.LinkedHashMap<String, byte[]>();
        tree.put(RepositoryPublishingPolicy.PATH, text("enabled: true\nmode: public-root\n"));
        tree.put("public/article.md", text("# Public\nContent"));
        fixture.commitRemote(workspace, tree);
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        var exports = new JGitRepositorySnapshotExports(
                fixture.authority(),
                auth,
                directory.toRealPath().resolve("exports"),
                1024 * 1024,
                Duration.ofSeconds(5),
                snapshots);
        var published = exports.createPublic(actor, workspace);
        exports.release(published.export().exportId());
        exports.requireCurrentPublic(actor, workspace, published);
        tree.put("private/new.md", text("private update"));
        fixture.commitRemote(workspace, tree);
        snapshots.refresh(workspace);
        exports.requireCurrentPublic(actor, workspace, published);
        assertThatThrownBy(() -> exports.requireCurrentPublic(actor, WorkspaceId.random(), published))
                .isInstanceOf(ContentRepositoryException.class);
        tree.put("public/article.md", text("# Public\nChanged publication"));
        fixture.commitRemote(workspace, tree);
        snapshots.refresh(workspace);
        assertThatThrownBy(() -> exports.requireCurrentPublic(actor, workspace, published))
                .isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void publicExportDenialPrecedesSnapshotAndRepositoryAccess() {
        RepositoryAuthority authority = mock(RepositoryAuthority.class);
        var snapshots = mock(io.github.core607.poketto.content.PublicContentSnapshots.class);
        doThrow(new SecurityException("denied"))
                .when(auth)
                .authorize(actor, workspace, io.github.core607.poketto.auth.Capability.EXECUTE_REPOSITORY);
        var exports = new JGitRepositorySnapshotExports(
                authority, auth, directory.resolve("exports"), 1024, Duration.ofSeconds(5), snapshots);
        assertThatThrownBy(() -> exports.createPublic(actor, workspace)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(authority, snapshots);
        assertThat(directory.resolve("exports")).doesNotExist();
    }

    @Test
    void withdrawingPublicationDuringExportLeavesNoBundleOrProjection() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        text("enabled: true\nmode: public-root\n"),
                        "public/article.md",
                        text("# Public\nContent")));
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        var exports = new JGitRepositorySnapshotExports(
                fixture.authority(),
                auth,
                directory.toRealPath().resolve("exports"),
                1024 * 1024,
                Duration.ofSeconds(5),
                snapshots);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
                    if (calls.incrementAndGet() == 2) {
                        fixture.commitRemote(
                                workspace,
                                Map.of(RepositoryPublishingPolicy.PATH, text("enabled: false\nmode: public-root\n")));
                        snapshots.refresh(workspace);
                    }
                    return null;
                })
                .when(auth)
                .authorize(actor, workspace, io.github.core607.poketto.auth.Capability.EXECUTE_REPOSITORY);
        assertThatThrownBy(() -> exports.createPublic(actor, workspace)).isInstanceOf(ContentRepositoryException.class);
        try (var files = Files.list(directory.resolve("exports"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void publicBundleContainsOnlyApprovedFieldsAndOneIndependentCommit() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var old = fixture.commitRemote(workspace, Map.of("private/secret.md", text("historic private needle")));
        var publicMedia = new RepositoryMediaIndex.Media(UUID.randomUUID(), "a".repeat(64), "application/pdf", 12);
        var hiddenMedia = new RepositoryMediaIndex.Media(UUID.randomUUID(), "b".repeat(64), "image/png", 15);
        var source = fixture.commitRemote(
                workspace,
                Map.of(
                        RepositoryPublishingPolicy.PATH,
                        text("enabled: true\nmode: public-root\n"),
                        RepositoryMediaIndex.PATH,
                        new RepositoryMediaIndex(
                                        Map.of("public/report.pdf", publicMedia, "private/secret.png", hiddenMedia))
                                .encode(),
                        "public/hello.md",
                        text("---\ntitle: Hello\nprivate_field: hidden frontmatter needle\n---\n"
                                + "Public body\n\n<!-- hidden comment needle -->\n\n"
                                + "[Report](report.pdf) [Other](other.md#heading) [Secret](../private/secret.md)\n\n"
                                + "![hidden](../private/secret.png)\n"),
                        "public/other.md",
                        text("# Other\nPublic second article"),
                        "AGENTS.md",
                        text("private operator instructions needle")));
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        var exports = new JGitRepositorySnapshotExports(
                fixture.authority(),
                auth,
                directory.toRealPath().resolve("exports"),
                1024 * 1024,
                Duration.ofSeconds(5),
                snapshots);
        var value = exports.createPublic(actor, workspace);
        assertThat(value.authorityCommit()).isEqualTo(source.name());
        assertThat(value.export().commit()).isNotEqualTo(source.name());
        assertThat(value.sourcePaths()).containsEntry("hello/index.md", "public/hello.md");
        Path bundle = directory.resolve("exports").resolve(value.export().exportId() + ".bundle");
        Path copy = directory.resolve("public-copy");
        try (Git clone = Git.cloneRepository()
                .setURI(bundle.toUri().toString())
                .setBranch("refs/heads/snapshot")
                .setDirectory(copy.toFile())
                .call()) {
            assertThat(clone.log().call()).hasSize(1);
            assertThat(clone.getRepository().resolve("HEAD^")).isNull();
            assertThat(clone.getRepository().getObjectDatabase().has(old)).isFalse();
            assertThat(clone.getRepository().getObjectDatabase().has(source)).isFalse();
            assertThat(copy.resolve("private")).doesNotExist();
            assertThat(copy.resolve(RepositoryPublishingPolicy.PATH)).doesNotExist();
            String article = Files.readString(copy.resolve("hello/index.md"));
            assertThat(article)
                    .contains("Public body", "../_media/1-report.pdf", "../other/index.md#heading")
                    .doesNotContain("private/", "hidden comment needle", "hidden frontmatter needle");
            assertThat(Files.readString(copy.resolve("AGENTS.md")))
                    .doesNotContain("private operator instructions needle");
            assertThat(RepositoryMediaIndex.parse(Files.readAllBytes(copy.resolve(RepositoryMediaIndex.PATH)))
                            .files())
                    .containsOnlyKeys("_media/1-report.pdf")
                    .containsValue(publicMedia)
                    .doesNotContainValue(hiddenMedia);
            assertThat(copy.resolve("_media/1-report.pdf")).doesNotExist();
            try (var reader = clone.getRepository().newObjectReader();
                    var objects = new org.eclipse.jgit.revwalk.ObjectWalk(reader)) {
                objects.markStart(objects.parseCommit(clone.getRepository().resolve("HEAD")));
                while (objects.next() != null) {}
                org.eclipse.jgit.revwalk.RevObject object;
                while ((object = objects.nextObject()) != null) {
                    if (object.getType() == org.eclipse.jgit.lib.Constants.OBJ_BLOB)
                        assertThat(new String(reader.open(object).getBytes(), StandardCharsets.UTF_8))
                                .doesNotContain(
                                        "private/secret",
                                        "historic private needle",
                                        "hidden frontmatter needle",
                                        "hidden comment needle",
                                        "private operator instructions needle",
                                        source.name());
                }
            }
        }
        verify(auth, atLeast(2))
                .authorize(actor, workspace, io.github.core607.poketto.auth.Capability.EXECUTE_REPOSITORY);
        verify(auth, never()).withAuthorization(any(), any(), any(), any());
        exports.release(value.export().exportId());
        try (var files = Files.list(directory.resolve("exports"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void exportsOnlyPinnedAncestryAndBytesWithoutSourceConfiguration() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        var first = fixture.commitRemote(workspace, Map.of("private/中文.md", text("# 原文\r\n")));
        var selected = fixture.commitRemote(workspace, Map.of("private/中文.md", text("# 第二版\r\n")));
        var future = fixture.commitRemote(
                workspace, Map.of("future.md", text("later content must not enter older session")));
        var exports = exporter(fixture, 1024 * 1024);
        var value = exports.create(actor, workspace, Optional.of(selected.name()));
        Path bundle = directory.resolve("exports").resolve(value.exportId() + ".bundle");
        assertThat(Files.size(bundle)).isEqualTo(value.bundleBytes());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bundle))))
                .isEqualTo(value.bundleSha256());
        Path copy = directory.resolve("execution-copy");
        try (Git clone = Git.cloneRepository()
                .setURI(bundle.toUri().toString())
                .setBranch("refs/heads/snapshot")
                .setDirectory(copy.toFile())
                .call()) {
            assertThat(clone.getRepository().resolve("HEAD").name()).isEqualTo(selected.name());
            assertThat(clone.getRepository().resolve("HEAD^").name()).isEqualTo(first.name());
            assertThat(clone.getRepository().getObjectDatabase().has(future)).isFalse();
            assertThat(Files.readString(copy.resolve("private/中文.md"))).isEqualTo("# 第二版\r\n");
            assertThat(Files.readString(copy.resolve(".git/config")))
                    .doesNotContain("password", "credential", "test remote");
            assertThat(copy.resolve(".git/objects/info/alternates")).doesNotExist();
            Files.writeString(copy.resolve("private/中文.md"), "changed only in isolated copy");
        }
        assertThat(new JGitRepositoryContentReader(fixture.authority())
                        .getFile(workspace, Optional.of(selected.name()), "private/中文.md")
                        .source())
                .contains("# 第二版\r\n");
        exports.release(value.exportId());
        assertThat(bundle).doesNotExist();
    }

    @Test
    void refusesForeignCommitsAndOversizedHistoryWithoutPublishingPartialExports() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, Map.of("large.txt", new byte[32_000]));
        var exports = exporter(fixture, 1024);
        assertThatThrownBy(() -> exports.create(actor, workspace, Optional.empty()))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> exports.create(actor, workspace, Optional.of("a".repeat(40))))
                .isInstanceOf(ContentRepositoryException.class);
        if (Files.exists(directory.resolve("exports"))) {
            try (var files = Files.list(directory.resolve("exports"))) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void deniedPrincipalNeverReadsRepositoryOrCreatesStagingFiles() {
        RepositoryAuthority authority = mock(RepositoryAuthority.class);
        when(auth.withAuthorization(any(), any(), any(), any())).thenThrow(new SecurityException("denied"));
        var exports = new JGitRepositorySnapshotExports(
                authority,
                auth,
                directory.resolve("exports"),
                1024,
                Duration.ofSeconds(5),
                mock(io.github.core607.poketto.content.PublicContentSnapshots.class));
        assertThatThrownBy(() -> exports.create(actor, workspace, Optional.empty()))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(authority);
        assertThat(directory.resolve("exports")).doesNotExist();
    }

    private JGitRepositorySnapshotExports exporter(RemoteRepositoryFixture fixture, long bytes) throws Exception {
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(3)).get());
        return new JGitRepositorySnapshotExports(
                fixture.authority(),
                auth,
                directory.toRealPath().resolve("exports"),
                bytes,
                Duration.ofSeconds(5),
                mock(io.github.core607.poketto.content.PublicContentSnapshots.class));
    }

    private static byte[] text(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
