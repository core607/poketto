package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.mcp.RepositoryExecutor.ArtifactMetadata;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Artifact handles return immutable application-owned captures without publishing or storing an original. */
final class NativeArtifactCommands {
    private final NativeRepositoryFiles files;
    private final NativeArtifacts artifacts;

    NativeArtifactCommands(NativeRepositoryFiles files, NativeArtifacts artifacts) {
        this.files = files;
        this.artifacts = artifacts;
    }

    BridgeReplies.Reply command(
            AccountCommand held, NativeRepositoryLease lease, String operation, JsonNode arguments) {
        lease.authorize();
        if (operation.equals("artifact_remove")) {
            String id = BridgeArguments.artifactRemove(arguments).artifactId();
            return artifacts.remove(held.record(), id)
                    ? BridgeReplies.removed()
                    : BridgeReplies.failed("ARTIFACT_UNAVAILABLE");
        }
        BridgeArguments.ArtifactCreate selected = BridgeArguments.artifactCreate(arguments);
        String path = RepositoryPaths.validate(selected.path());
        String name = path.substring(path.lastIndexOf('/') + 1);
        new ArtifactMetadata(UUID.randomUUID().toString(), name, selected.mediaType(), 0, "0".repeat(64), false, 300);
        NativeTransferStore.File file = files.capture(lease, path, NativeArtifacts.MAX_FILE);
        boolean retained = false;
        try {
            lease.authorize();
            ArtifactMetadata result = artifacts.retain(held.record(), name, selected.mediaType(), false, file);
            retained = true;
            return BridgeReplies.artifact(result);
        } finally {
            if (!retained) {
                file.close();
            }
        }
    }
}
