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

    BridgeReplies.Reply run(AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, Files files) {
        if (state.sync == null) {
            WorkspaceSyncInputs inputs = saves.prepareWorkspaceSync(actor, workspace, state);
            List<String> paths = ordered(inputs);
            state.retainSync(new PendingWorkspaceSync(inputs.remoteCommit(), paths, 0, List.of(), null));
        }
        while (state.sync.next() < state.sync.paths().size()) {
            if (state.sync.current() == null) {
                String path = state.sync.paths().get(state.sync.next());
                PendingWorkspaceSync.File selected = plan(actor, workspace, state, path, files);
                state.retainSync(state.sync.prepared(selected));
            }
            PendingWorkspaceSync.File selected = state.sync.current();
            if (selected.install() && !files.install(selected)) {
                return BridgeReplies.failedWith(
                        "LOCAL_SYNC_PENDING",
                        progress(state.sync),
                        "The local file changed. Its contents are preserved; recover this synchronization or release it with poketto recover --skip-local.");
            }
            state.acknowledgeSyncFile();
        }
        return state.finishSync(false);
    }

    static BridgeReplies.WorkspaceSyncResult progress(PendingWorkspaceSync pending) {
        return BridgeReplies.workspaceSyncResult(pending, false);
    }

    private static List<String> ordered(WorkspaceSyncInputs inputs) {
        var paths = new ArrayList<>(inputs.paths());
        paths.sort(Comparator.<String>comparingInt(path -> leaf(inputs.remote().get(path)) ? 1 : 0)
                .thenComparingInt(path -> (leaf(inputs.remote().get(path)) ? 1 : -1) * path.split("/").length)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(paths);
    }

    private static boolean leaf(RepositoryFile file) {
        return file != null
                && !file.expectedAbsence()
                && !diagnostic(file, "NOT_REGULAR_FILE")
                && !diagnostic(file, "MANAGED_MEDIA");
    }

    private PendingWorkspaceSync.File plan(
            AuthPrincipal actor, WorkspaceId workspace, SelectedFileSaves.State state, String path, Files files) {
        RepositoryFile base = saves.baselineFile(actor, workspace, state, path);
        RepositoryFile remote = saves.repository().getFile(actor, workspace, Optional.of(state.sync.commit()), path);
        RepositorySyncEntry before = blob(actor, workspace, base);
        RepositorySyncEntry after = blob(actor, workspace, remote);
        RetainedFileBaseline baseline = retained(workspace, remote, after);
        if (before.kind() == RepositorySyncEntry.Kind.UNSUPPORTED
                || after.kind() == RepositorySyncEntry.Kind.UNSUPPORTED) {
            return unchanged(path, baseline, true);
        }
        if (before.kind() != RepositorySyncEntry.Kind.FILE && after.kind() != RepositorySyncEntry.Kind.FILE) {
            return unchanged(path, baseline, false);
        }
        if (Objects.equals(before.sha256(), after.sha256())) {
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

    private RepositorySyncEntry blob(AuthPrincipal actor, WorkspaceId workspace, RepositoryFile file) {
        String commit = file.commit().orElseThrow(() -> new IllegalArgumentException("sync file commit is missing"));
        if (file.expectedAbsence() || diagnostic(file, "MANAGED_MEDIA")) {
            return new RepositorySyncEntry(commit, file.path(), RepositorySyncEntry.Kind.ABSENT, 0, null);
        }
        if (file.source().isPresent()) {
            byte[] bytes = file.source().orElseThrow().getBytes(StandardCharsets.UTF_8);
            return new RepositorySyncEntry(
                    commit,
                    file.path(),
                    RepositorySyncEntry.Kind.FILE,
                    bytes.length,
                    DocumentRevision.sha256(bytes).value().substring(7));
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
