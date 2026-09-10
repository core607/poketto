package io.github.core607.poketto.assets;

import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryOriginalTransfers;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.Supplier;

/** Connects authorized repository operations to immutable workspace-owned storage. */
public final class ManagedOriginalTransfers implements RepositoryOriginalTransfers {
    private final Supplier<ManagedBlobStore> originals;

    public ManagedOriginalTransfers(Supplier<ManagedBlobStore> originals) {
        this.originals = originals;
    }

    @Override
    public RepositoryMediaIndex.Media describe(WorkspaceId workspace, UUID identity, String revision) {
        var asset = originals.get().describe(workspace, new ManagedAssetReference(identity, revision));
        return new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
    }

    @Override
    public void copyTo(WorkspaceId workspace, UUID identity, String revision, OutputStream output) {
        originals.get().copyTo(workspace, new ManagedAssetReference(identity, revision), output);
    }
}
