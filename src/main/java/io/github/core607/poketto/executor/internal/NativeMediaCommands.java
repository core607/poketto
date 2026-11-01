package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Originals remain application-owned; import and link propose index entries without replacing local edits. */
final class NativeMediaCommands {
    private static final String INDEX_MESSAGE = "Original is stored. The local media index is unchanged. "
            + "Explicitly merge indexEntry into the files map at the returned path in .poketto/assets.json, "
            + "then save that index with the referring text.";
    private final NativeRepositoryFiles files;
    private final MediaFileService media;
    private final SelectedFileSaves saves;
    private final AuthService auth;

    NativeMediaCommands(
            NativeRepositoryFiles files, MediaFileService media, SelectedFileSaves saves, AuthService auth) {
        this.files = files;
        this.media = media;
        this.saves = saves;
        this.auth = auth;
    }

    BridgeReplies.Reply command(
            AuthPrincipal principal,
            AccountCommand held,
            NativeRepositoryLease lease,
            String operation,
            JsonNode arguments) {
        lease.authorize();
        var workspace = new WorkspaceId(held.owner().workspaceId());
        return switch (operation) {
            case "media_list" -> list(principal, workspace, held, lease, arguments);
            case "media_fetch" -> fetch(principal, workspace, held, lease, arguments);
            case "media_import", "media_link" -> propose(principal, workspace, held, lease, operation, arguments);
            default -> throw new IllegalArgumentException("Unknown native media operation");
        };
    }

    private BridgeReplies.Reply list(
            AuthPrincipal principal,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            JsonNode arguments) {
        MediaListing.Query query = MediaListing.Query.parse(arguments);
        Map<String, RepositoryMediaIndex.Media> entries;
        String version;
        String source;
        if (!held.owner().fullRead()) {
            if (query.commit() != null) {
                throw new IllegalArgumentException("Public media uses its current projection");
            }
            entries = new LinkedHashMap<>();
            held.record().publicExport().media().forEach((path, approved) -> entries.put(path, approved.original()));
            version = held.record().publicExport().projectionSha256();
            source = "public-projection";
        } else if (query.commit() != null) {
            var catalog = media.privateCatalog(principal, workspace, Optional.of(query.commit()));
            entries = catalog.index().files();
            version = SandcrabProtocol.sha256(catalog.commit().getBytes(StandardCharsets.UTF_8));
            source = "repository";
        } else {
            Optional<byte[]> captured =
                    files.optional(lease, RepositoryMediaIndex.PATH, RepositoryMediaIndex.MAX_BYTES);
            entries = captured.map(RepositoryMediaIndex::parse)
                    .orElseGet(RepositoryMediaIndex::empty)
                    .files();
            version = SandcrabProtocol.sha256(captured.orElseGet(() -> new byte[0]));
            source = "worktree";
        }
        lease.authorize();
        return MediaListing.page(entries, query, version, source);
    }

    private BridgeReplies.Reply fetch(
            AuthPrincipal principal,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            JsonNode arguments) {
        BridgeArguments.MediaFetch selected = BridgeArguments.mediaFetch(arguments);
        String destination = RepositoryPaths.validate(selected.output() == null ? selected.path() : selected.output());
        Download download = download(principal, workspace, held, lease, selected);
        ManagedAsset asset = download.source().asset();
        if (!files.install(
                lease, destination, asset.size(), asset.reference().revision(), download.source()::writeTo)) {
            return BridgeReplies.failed(
                    "LOCAL_FILE_EXISTS", "A different local file exists; keep it or choose another --output path.");
        }
        return BridgeReplies.succeeded(new BridgeReplies.FetchResult(
                destination,
                selected.path(),
                download.indexSource(),
                download.commit(),
                asset.reference().revision(),
                asset.mediaType(),
                asset.size()));
    }

    private Download download(
            AuthPrincipal principal,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            BridgeArguments.MediaFetch selected) {
        if (!held.owner().fullRead()) {
            if (selected.commit() != null) {
                throw new IllegalArgumentException("Public media uses its current projection");
            }
            var projection = held.record().publicExport();
            var approved = projection.media().get(selected.path());
            if (approved == null) {
                throw new IllegalArgumentException("Media path is not present in the public projection");
            }
            var result = media.memberProjectionDownload(
                    principal,
                    workspace,
                    approved.route(),
                    projection.sourcePaths().get(selected.path()));
            ManagedAsset asset = result.asset();
            if (!entry(asset).equals(approved.original())) {
                throw new WorkerUnavailableException();
            }
            return new Download(result, "public-projection", projection.export().commit());
        }
        if (selected.commit() != null) {
            String commit = ProtocolValues.hex(selected.commit(), 40, "media commit");
            return new Download(
                    media.privateDownload(principal, workspace, Optional.of(commit), selected.path()),
                    "repository",
                    commit);
        }
        RepositoryMediaIndex index = files.optional(lease, RepositoryMediaIndex.PATH, RepositoryMediaIndex.MAX_BYTES)
                .map(RepositoryMediaIndex::parse)
                .orElseGet(RepositoryMediaIndex::empty);
        return new Download(
                media.privateOriginal(
                        principal, workspace, selected.path(), index.files().get(selected.path())),
                "worktree",
                null);
    }

    private BridgeReplies.Reply propose(
            AuthPrincipal principal,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            String operation,
            JsonNode arguments) {
        if (!held.owner().fullRead()) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        auth.authorize(principal, workspace, Capability.WRITE_PRIVATE);
        SelectedFileSaves.State state = held.state();
        Optional<byte[]> source = files.optional(lease, RepositoryMediaIndex.PATH, RepositoryMediaIndex.MAX_BYTES);
        if (source.isEmpty()
                && !saves.baselineFile(principal, workspace, state, RepositoryMediaIndex.PATH)
                        .expectedAbsence()) {
            return BridgeReplies.failed(
                    "INDEX_MISSING", "Restore the local media index before importing or linking an original.");
        }
        RepositoryMediaIndex index = source.map(RepositoryMediaIndex::parse).orElseGet(RepositoryMediaIndex::empty);
        if (operation.equals("media_import")) {
            return importOriginal(
                    principal, workspace, held, lease, state, index, BridgeArguments.mediaImport(arguments));
        }
        BridgeArguments.MediaLink selected = BridgeArguments.mediaLink(arguments);
        ManagedAsset asset = media.describeOriginal(principal, workspace, selected.reference());
        if (!available(principal, workspace, state, index, selected.path(), asset.mediaType())) {
            return BridgeReplies.failed("MEDIA_PATH_COLLIDES_WITH_GIT");
        }
        return proposal(state, index, selected.path(), asset, selected.replace());
    }

    private BridgeReplies.Reply importOriginal(
            AuthPrincipal principal,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            SelectedFileSaves.State state,
            RepositoryMediaIndex index,
            BridgeArguments.MediaImport selected) {
        if (!available(principal, workspace, state, index, selected.path(), selected.mediaType())) {
            return BridgeReplies.failed("MEDIA_PATH_COLLIDES_WITH_GIT");
        }
        try (NativeTransferStore.File captured = files.capture(lease, selected.file(), NativeArtifacts.MAX_FILE)) {
            RepositoryMediaIndex.Media previous = index.files().get(selected.path());
            if (previous != null
                    && !selected.replace()
                    && (previous.size() != captured.size()
                            || !previous.revision().equals(captured.sha256())
                            || !previous.mediaType().equals(selected.mediaType()))) {
                return BridgeReplies.failed("MEDIA_PATH_EXISTS");
            }
            ManagedAsset asset;
            try (InputStream input = captured.open()) {
                asset = media.upload(principal, workspace, selected.key(), selected.mediaType(), input);
            }
            if (asset.size() != captured.size()
                    || !asset.reference().revision().equals(captured.sha256())
                    || !asset.mediaType().equals(selected.mediaType())) {
                throw new WorkerUnavailableException();
            }
            lease.authorize();
            return proposal(state, index, selected.path(), asset, selected.replace());
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    private boolean available(
            AuthPrincipal principal,
            WorkspaceId workspace,
            SelectedFileSaves.State state,
            RepositoryMediaIndex index,
            String path,
            String mediaType) {
        var entries = new LinkedHashMap<>(index.files());
        entries.put(path, new RepositoryMediaIndex.Media(new UUID(0, 0), "0".repeat(64), mediaType, 1));
        new RepositoryMediaIndex(entries);
        RepositoryFile baseline = saves.baselineFile(principal, workspace, state, path);
        return baseline.expectedAbsence()
                || baseline.diagnostics().stream()
                        .anyMatch(value -> value.code().equals("MANAGED_MEDIA"));
    }

    private static BridgeReplies.Reply proposal(
            SelectedFileSaves.State state,
            RepositoryMediaIndex index,
            String path,
            ManagedAsset asset,
            boolean replace) {
        var receipt = new BridgeReplies.ImportReceipt(
                path,
                asset.reference().assetId().toString(),
                asset.reference().revision(),
                asset.mediaType(),
                asset.size(),
                true,
                false,
                false);
        state.acknowledgeImport(receipt);
        RepositoryMediaIndex.Media proposed = entry(asset);
        RepositoryMediaIndex.Media previous = index.files().get(path);
        if (previous != null && !previous.equals(proposed) && !replace) {
            return BridgeReplies.failedWith("MEDIA_PATH_EXISTS", state.lastImport);
        }
        return BridgeReplies.succeededWithMessage(
                new IndexProposal(
                        path,
                        receipt.assetId(),
                        receipt.sha256(),
                        receipt.mediaType(),
                        receipt.bytes(),
                        true,
                        false,
                        false,
                        RepositoryMediaIndex.PATH,
                        proposed),
                INDEX_MESSAGE);
    }

    private static RepositoryMediaIndex.Media entry(ManagedAsset asset) {
        return new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
    }

    private record Download(MediaFileService.Download source, String indexSource, String commit) {}

    record IndexProposal(
            String path,
            String assetId,
            String sha256,
            String mediaType,
            long bytes,
            boolean originalStored,
            boolean indexUpdated,
            boolean saved,
            String indexPath,
            RepositoryMediaIndex.Media indexEntry) {}
}
