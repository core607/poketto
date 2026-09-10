package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Already-authorized original reads. Every identity is resolved strictly within the supplied workspace. */
public interface RepositoryOriginalTransfers {
    RepositoryMediaIndex.Media describe(WorkspaceId workspace, UUID identity, String revision);

    /** Verifies original bytes and leaves caller output open. A failed transfer must not publish its destination. */
    void copyTo(WorkspaceId workspace, UUID identity, String revision, OutputStream output);

    /** Validates a legacy public image through the image-preview policy before writing any bytes. */
    void copyImageTo(WorkspaceId workspace, UUID identity, String revision, OutputStream output) throws IOException;
}
