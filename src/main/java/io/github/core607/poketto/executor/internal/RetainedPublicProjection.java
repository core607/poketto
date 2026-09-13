package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.content.internal.RepositoryPathRules;
import java.util.Objects;

/** Host-owned publication proof must survive with the worker's public projection. */
final class RetainedPublicProjection {
    private RetainedPublicProjection() {}

    static void validate(
            RetainedCopyRecord.Owner owner,
            boolean fullRead,
            RepositorySnapshotExports.PublicExport projection,
            String originalCommit) {
        if (fullRead) {
            require(projection == null, "full copy", "must not carry public projection authority");
            return;
        }
        Objects.requireNonNull(projection, "public copy requires its publication proof");
        require(
                owner.workspaceId().equals(projection.workspaceId().value()),
                "public projection",
                "must match workspace");
        var export = projection.export();
        Objects.requireNonNull(export.exportId(), "public export identity must be present");
        require(originalCommit.equals(export.commit()), "public projection", "must match the pinned worker commit");
        ProtocolValues.hex(export.bundleSha256(), 64, "public export digest");
        ProtocolValues.inRange(export.bundleBytes(), 1, 1024L * 1024 * 1024, "public export bytes");
        ProtocolValues.hex(projection.authorityCommit(), 40, "public authority commit");
        ProtocolValues.hex(projection.projectionSha256(), 64, "public projection fingerprint");
        require(projection.sourcePaths().size() <= 100_000, "public mapping", "exceeds the export object bound");
        projection.sourcePaths().forEach((path, source) -> {
            RepositoryPathRules.validate(path);
            RepositoryPathRules.validate(source);
        });
        projection.media().values().forEach(media -> {
            Objects.requireNonNull(media.original(), "public media original must be present");
            require(media.route() != null && media.route().startsWith("/"), "public media route", "must be absolute");
        });
    }
}
