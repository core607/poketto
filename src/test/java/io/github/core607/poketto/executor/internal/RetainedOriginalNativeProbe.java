package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Real CLI recovery with a reader that refuses every historical baseline query. */
record RetainedOriginalNativeProbe(
        AuthService auth, AuthPrincipal actor, WorkspaceId workspace, ExecutionCancellation cancellation) {
    void run(Path root, PublicExecutionNativeFixture fixture, Adapter adapter) {
        var records = new RetainedCopyStore(
                root.resolve("original-read-records"),
                new RetainedCopyStore.Limits(2, 8 * 1024 * 1024, 64 * 1024 * 1024, 0, Duration.ofMinutes(10)),
                Clock.systemUTC());
        RetainedCopyRecord original = create(
                records,
                fixture,
                adapter,
                "original-save",
                "printf 'local archived edit' > AGENTS.md; printf 'new archived path' > private/archive-created.md");
        try (var executor = adapter.open(records, unavailableHistory(fixture))) {
            run(
                    executor,
                    "original-save-resumed",
                    resume(original),
                    "set -eu; poketto save AGENTS.md private/archive-created.md; "
                            + "test \"$(cat AGENTS.md)\" = 'local archived edit'");
            assertThat(records.read(original.owner(), original.copyId()).originalBaseline())
                    .isEqualTo(original.originalBaseline());
        }
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "private/archive-created.md")
                        .source())
                .contains("new archived path");
        RetainedCopyRecord syncing =
                create(records, fixture, adapter, "original-sync", "printf 'competing local edit' > AGENTS.md");
        fixture.competingWrite(auth, actor);
        try (var executor = adapter.open(records, unavailableHistory(fixture))) {
            run(
                    executor,
                    "original-sync-resumed",
                    resume(syncing),
                    "set -eu; if poketto save AGENTS.md > /tmp/refusal; then exit 95; fi; "
                            + "grep -q REPOSITORY_CONFLICT /tmp/refusal; "
                            + "if poketto sync AGENTS.md > /tmp/sync; then exit 96; fi; "
                            + "grep -q MERGE_CONFLICT /tmp/sync; "
                            + "grep -q 'externally-updated-guide' AGENTS.md; "
                            + "printf 'resolved from original archive' > AGENTS.md; poketto save AGENTS.md");
            var after = records.read(syncing.owner(), syncing.copyId());
            assertThat(after.acknowledged().state().originalCommit())
                    .isEqualTo(syncing.acknowledged().state().originalCommit());
            assertThat(after.originalBaseline()).isEqualTo(syncing.originalBaseline());
        }
        assertThat(fixture.reader(auth)
                        .getFile(actor, workspace, Optional.empty(), "AGENTS.md")
                        .source())
                .contains("resolved from original archive");
    }

    private RetainedCopyRecord create(
            RetainedCopyStore records,
            PublicExecutionNativeFixture fixture,
            Adapter adapter,
            String transport,
            String command) {
        try (var executor = adapter.open(records, fixture.reader(auth))) {
            var result = run(executor, transport, new RepositoryExecutor.CopyRequest("new", null, false), command);
            return records.read(
                    new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value()),
                    UUID.fromString(result.copyId()));
        }
    }

    private static RepositoryExecutor.CopyRequest resume(RetainedCopyRecord record) {
        return new RepositoryExecutor.CopyRequest(record.copyId().toString(), record.generation(), true);
    }

    private RepositoryExecutor.ExecutionResult run(
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

    private AuthorizedRepositoryReader unavailableHistory(PublicExecutionNativeFixture fixture) {
        var reader = mock(AuthorizedRepositoryReader.class);
        var current = fixture.reader(auth);
        when(reader.getFile(any(), any(), any(), any())).thenAnswer(call -> {
            Optional<String> commit = call.getArgument(2);
            if (commit.isPresent()) {
                throw new ContentRepositoryException("native historical baseline reader unavailable");
            }
            return current.getFile(call.getArgument(0), call.getArgument(1), commit, call.getArgument(3));
        });
        return reader;
    }

    @FunctionalInterface
    interface Adapter {
        IsolatedRepositoryExecutor open(RetainedCopyStore records, AuthorizedRepositoryReader reader);
    }
}
