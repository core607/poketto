package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
                authority, auth, directory.resolve("exports"), 1024, Duration.ofSeconds(5));
        assertThatThrownBy(() -> exports.create(actor, workspace, Optional.empty()))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(authority);
        assertThat(directory.resolve("exports")).doesNotExist();
    }

    private JGitRepositorySnapshotExports exporter(RemoteRepositoryFixture fixture, long bytes) throws Exception {
        when(auth.withAuthorization(any(), any(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(3)).get());
        return new JGitRepositorySnapshotExports(
                fixture.authority(), auth, directory.toRealPath().resolve("exports"), bytes, Duration.ofSeconds(5));
    }

    private static byte[] text(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
