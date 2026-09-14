package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One admitted command lazily opens its immutable original once; the caller rechecks current authorization. */
final class RetainedOriginalLookup implements OriginalFileLookup, AutoCloseable {
    private final RetainedBaselineStore store;
    private final RetainedFileLocks.Held writer;
    private final RetainedBaseline.Reference reference;
    private RetainedBaselineStore.Reader reader;
    private boolean closed;

    RetainedOriginalLookup(
            RetainedBaselineStore store, RetainedFileLocks.Held writer, RetainedBaseline.Reference reference) {
        this.store = Objects.requireNonNull(store, "original store must be present");
        this.writer = Objects.requireNonNull(writer, "original writer must be present");
        this.reference = Objects.requireNonNull(reference, "original reference must be present");
    }

    @Override
    public RepositoryFile file(AuthPrincipal actor, WorkspaceId workspace, String commit, String path) {
        if (closed) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE);
        }
        requireIdentity(actor, workspace, commit);
        if (reader == null) {
            reader = store.open(writer, reference);
        }
        return reader.find(path)
                .orElseGet(() -> new RepositoryFile(
                        workspace,
                        Optional.of(commit),
                        path,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        false));
    }

    private void requireIdentity(AuthPrincipal actor, WorkspaceId workspace, String commit) {
        var expected = new RetainedCopyRecord.Owner(actor.subjectId(), workspace.value());
        if (!expected.equals(reference.identity().owner())) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
        if (!commit.equals(reference.identity().commit())) {
            throw new RetainedCopyException(RetainedCopyException.Reason.STALE);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (reader != null) {
            reader.close();
        }
    }
}
