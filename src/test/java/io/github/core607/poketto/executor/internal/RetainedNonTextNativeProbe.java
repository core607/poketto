package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.executor.internal.RetainedOriginalNativeProbe.Adapter;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Restored CLI media decisions use retained non-text presence, not unavailable history or false absence. */
record RetainedNonTextNativeProbe(
        AuthService auth, AuthPrincipal actor, WorkspaceId workspace, ExecutionCancellation cancellation) {
    void run(Path root, PublicExecutionNativeFixture fixture, Adapter adapter) throws Exception {
        fixture.seedFile("private/git.bin", new byte[] {0, 1, -1, 10});
        fixture.seedMedia(auth, actor, new byte[] {7, 8}, new byte[] {0, -1});
        String index = fixture.reader(auth)
                .getFile(actor, workspace, Optional.empty(), RepositoryMediaIndex.PATH)
                .source()
                .orElseThrow();
        var media = RepositoryMediaIndex.parse(index.getBytes(StandardCharsets.UTF_8))
                .files()
                .get("private/manual.pdf");
        String reference = " --asset " + media.assetId() + " --revision " + media.revision();
        var records = new RetainedCopyStore(
                root.resolve("nontext-records"),
                new RetainedCopyStore.Limits(2, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        RetainedCopyRecord original;
        try (var executor = adapter.open(records, fixture.reader(auth))) {
            var result = execute(
                    executor,
                    "nontext-first",
                    new RepositoryExecutor.CopyRequest("new", null, false),
                    "set -eu; poketto move private/git.bin private/moved.bin; "
                            + "poketto media fetch private/manual.pdf; "
                            + "poketto move private/manual.pdf private/relocated.pdf");
            original = records.read(
                    new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value()),
                    UUID.fromString(result.copyId()));
            requireVersions(original);
        }
        try (var executor = adapter.open(records, unavailableHistory())) {
            execute(
                    executor,
                    "nontext-resumed",
                    new RepositoryExecutor.CopyRequest(original.copyId().toString(), 1L, true),
                    "set -eu; python3 -c \"from pathlib import Path; "
                            + "assert Path('private/moved.bin').read_bytes() == bytes([0,1,255,10]); "
                            + "assert Path('private/relocated.pdf').read_bytes() == bytes([0,255])\"; "
                            + "if poketto media link private/moved.bin" + reference
                            + " > /tmp/collision; then exit 95; fi; "
                            + "grep -q MEDIA_PATH_COLLIDES_WITH_GIT /tmp/collision; "
                            + "poketto media link private/relocated.pdf" + reference + "; "
                            + "if poketto save --delete private/moved.bin > /tmp/refusal; then exit 96; fi; "
                            + "grep -q NO_WRITABLE_BASELINE /tmp/refusal; poketto status");
            var after = records.read(original.owner(), original.copyId());
            requireVersions(after);
            assertThat(after.originalBaseline()).isEqualTo(original.originalBaseline());
            assertThat(after.acknowledged().state().uncertain()).isFalse();
        }
    }

    private void requireVersions(RetainedCopyRecord record) {
        var files = record.acknowledged().state().fileBaselines();
        var git = files.get("private/moved.bin").file(workspace, "private/moved.bin");
        var managed = files.get("private/relocated.pdf").file(workspace, "private/relocated.pdf");
        assertThat(git.expectedAbsence()).isFalse();
        assertThat(git.revision()).contains(DocumentRevision.sha256(new byte[] {0, 1, -1, 10}));
        assertThat(git.diagnostics()).extracting(value -> value.code()).containsExactly("INVALID_UTF8");
        assertThat(managed.expectedAbsence()).isFalse();
        assertThat(managed.revision()).isEmpty();
        assertThat(managed.diagnostics()).extracting(value -> value.code()).containsExactly("MANAGED_MEDIA");
        assertThat(files.get("private/git.bin").content()).isInstanceOf(RetainedFileBaseline.Absent.class);
        assertThat(files.get("private/manual.pdf").content()).isInstanceOf(RetainedFileBaseline.Absent.class);
    }

    private RepositoryExecutor.ExecutionResult execute(
            IsolatedRepositoryExecutor executor,
            String transport,
            RepositoryExecutor.CopyRequest copy,
            String command) {
        var result = executor.execute(
                actor, workspace, transport, copy, Optional.empty(), command, Duration.ofSeconds(30), cancellation);
        assertThat(result.exitCode())
                .as("%s %s", result.stdout(), result.stderr())
                .isZero();
        return result;
    }

    private AuthorizedRepositoryReader unavailableHistory() {
        var reader = mock(AuthorizedRepositoryReader.class);
        when(reader.getFile(any(), any(), any(), any()))
                .thenThrow(new ContentRepositoryException("non-text historical baseline unavailable"));
        return reader;
    }
}
