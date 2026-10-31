package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositorySyncEntry;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Serial local reconciliation. Each exact installation is journaled before the worker can apply it. */
final class WorkspaceSynchronization {
    interface Files {
        Local read(String path);

        boolean install(PendingWorkspaceSync.File file);
    }

    record Local(String sha256, String text, boolean unsafe) {}

    private final SelectedFileSaves saves;

    WorkspaceSynchronization(SelectedFileSaves saves) {
        this.saves = saves;
    }

    /**
     * Runs until every path is reconciled, or until {@code outOfTime} reports that the command's
     * time is spent. Each run reconciles at least one path, so repeated runs always finish.
     */
    BridgeReplies.Reply run(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            BooleanSupplier outOfTime,
            Files files) {
        WorkspaceSyncInputs inputs = saves.prepareWorkspaceSync(actor, workspace, state);
        if (state.sync == null) {
            List<String> paths = ordered(inputs);
            state.retainSync(new PendingWorkspaceSync(inputs.remoteCommit(), paths, 0, List.of(), null));
        }
        SelectedFileSaves.State progress = state.copy();
        boolean planned = false;
        while (progress.sync.next() < progress.sync.paths().size()) {
            if (progress.sync.current() == null) {
                if (planned && outOfTime.getAsBoolean()) {
                    state.install(progress);
                    return BridgeReplies.failedWith(
                            "SYNC_INCOMPLETE",
                            progress(state.sync),
                            "Command time ran out; progress is retained. Run poketto sync again to continue, or give repo_exec a larger timeoutSeconds.");
                }
                planned = true;
                String path = progress.sync.paths().get(progress.sync.next());
                PendingWorkspaceSync.File selected = plan(actor, workspace, progress, inputs, path, files);
                progress.sync = progress.sync.prepared(selected);
            }
            PendingWorkspaceSync.File selected = progress.sync.current();
            if (selected.install()) {
                state.install(progress);
                if (!files.install(selected)) {
                    return BridgeReplies.failedWith(
                            "LOCAL_SYNC_PENDING",
                            progress(state.sync),
                            "The local file changed. Its contents are preserved; recover this synchronization or release it with poketto recover --skip-local.");
                }
            }
            progress.advanceSyncFile();
        }
        state.install(progress);
        return state.finishSync(false);
    }

    static BridgeReplies.WorkspaceSyncResult progress(PendingWorkspaceSync pending) {
        return BridgeReplies.workspaceSyncResult(pending, false);
    }

    static List<String> ordered(WorkspaceSyncInputs inputs) {
        var paths = new ArrayList<>(inputs.paths());
        paths.sort(Comparator.<String>comparingInt(path -> leaf(inputs.remote().get(path)) ? 1 : 0)
                .thenComparingInt(path -> (leaf(inputs.remote().get(path)) ? 1 : -1) * path.split("/").length)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(paths);
    }

    static boolean leaf(RepositoryFile file) {
        return file != null
                && !file.expectedAbsence()
                && !diagnostic(file, "NOT_REGULAR_FILE")
                && !diagnostic(file, "MANAGED_MEDIA");
    }

    /** Unchanged authoritative bytes need no local capture or new per-file baseline. */
    boolean requiresReconciliation(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            WorkspaceSyncInputs inputs,
            String path) {
        RepositoryFile base = inputs.baseline().getOrDefault(path, absent(workspace, state.baseline(path), path));
        RepositoryFile remote = inputs.remote().getOrDefault(path, absent(workspace, inputs.remoteCommit(), path));
        return !base.equals(remote) && !unchanged(blob(actor, workspace, base), blob(actor, workspace, remote));
    }

    private static boolean unchanged(RepositorySyncEntry before, RepositorySyncEntry after) {
        return before.kind() != RepositorySyncEntry.Kind.UNSUPPORTED
                && after.kind() != RepositorySyncEntry.Kind.UNSUPPORTED
                && ((before.kind() != RepositorySyncEntry.Kind.FILE && after.kind() != RepositorySyncEntry.Kind.FILE)
                        || Objects.equals(before.sha256(), after.sha256()));
    }

    PendingWorkspaceSync.File plan(
            AuthPrincipal actor,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            WorkspaceSyncInputs inputs,
            String path,
            Files files) {
        RepositoryFile base = inputs.baseline().getOrDefault(path, absent(workspace, state.baseline(path), path));
        RepositoryFile remote = inputs.remote().getOrDefault(path, absent(workspace, inputs.remoteCommit(), path));
        if (base.equals(remote)) {
            return unchanged(path, RetainedFileBaseline.capture(workspace, inputs.remoteCommit(), path, remote), false);
        }
        RepositorySyncEntry before = blob(actor, workspace, base);
        RepositorySyncEntry after = blob(actor, workspace, remote);
        RetainedFileBaseline baseline = retained(workspace, remote, after);
        if (before.kind() == RepositorySyncEntry.Kind.UNSUPPORTED
                || after.kind() == RepositorySyncEntry.Kind.UNSUPPORTED) {
            return unchanged(path, baseline, true);
        }
        if (unchanged(before, after)) {
            return unchanged(path, baseline, false);
        }
        Local local = files.read(path);
        if (local.unsafe()) {
            return unchanged(path, baseline, true);
        }
        if (Objects.equals(local.sha256(), after.sha256())) {
            return unchanged(path, baseline, false);
        }
        if (text(before, base) && text(after, remote) && (local.sha256() == null || local.text() != null)) {
            var merged = TextReconciliation.merge(base.source(), Optional.ofNullable(local.text()), remote.source());
            return new PendingWorkspaceSync.File(
                    path,
                    local.sha256(),
                    merged.content().orElse(null),
                    null,
                    merged.content().isEmpty(),
                    true,
                    baseline,
                    merged.conflicted());
        }
        if (!Objects.equals(local.sha256(), before.sha256())) {
            return unchanged(path, baseline, true);
        }
        return new PendingWorkspaceSync.File(
                path,
                local.sha256(),
                null,
                after.kind() == RepositorySyncEntry.Kind.FILE ? after : null,
                after.kind() != RepositorySyncEntry.Kind.FILE,
                true,
                baseline,
                false);
    }

    private static boolean text(RepositorySyncEntry blob, RepositoryFile file) {
        return blob.kind() != RepositorySyncEntry.Kind.FILE || file.source().isPresent();
    }

    private static RepositoryFile absent(WorkspaceId workspace, String commit, String path) {
        return new RepositoryFile(
                workspace, Optional.of(commit), path, true, Optional.empty(), Optional.empty(), List.of(), false);
    }

    private RepositorySyncEntry blob(AuthPrincipal actor, WorkspaceId workspace, RepositoryFile file) {
        String commit = file.commit().orElseThrow(() -> new IllegalArgumentException("sync file commit is missing"));
        if (file.expectedAbsence() || diagnostic(file, "MANAGED_MEDIA")) {
            return new RepositorySyncEntry(commit, file.path(), RepositorySyncEntry.Kind.ABSENT, 0, null);
        }
        if (file.source().isPresent()) {
            byte[] bytes = file.source().orElseThrow().getBytes(StandardCharsets.UTF_8);
            return new RepositorySyncEntry(
                    commit, file.path(), RepositorySyncEntry.Kind.FILE, bytes.length, SandcrabProtocol.sha256(bytes));
        }
        return saves.repository().inspectBlob(actor, workspace, commit, file.path());
    }

    private static RetainedFileBaseline retained(WorkspaceId workspace, RepositoryFile file, RepositorySyncEntry blob) {
        RepositoryFile observed = file;
        if (blob.kind() == RepositorySyncEntry.Kind.FILE && file.revision().isEmpty()) {
            observed = new RepositoryFile(
                    workspace,
                    file.commit(),
                    file.path(),
                    false,
                    file.source(),
                    Optional.of(new DocumentRevision("sha256:" + blob.sha256())),
                    file.diagnostics(),
                    file.publicScope());
        }
        return RetainedFileBaseline.capture(workspace, file.commit().orElseThrow(), file.path(), observed);
    }

    private static PendingWorkspaceSync.File unchanged(String path, RetainedFileBaseline baseline, boolean conflict) {
        return new PendingWorkspaceSync.File(path, null, null, null, false, false, baseline, conflict);
    }

    private static boolean diagnostic(RepositoryFile file, String code) {
        return file.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(code));
    }
}
