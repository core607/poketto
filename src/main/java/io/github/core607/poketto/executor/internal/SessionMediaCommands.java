package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SessionWorker.hash;
import static io.github.core607.poketto.executor.internal.SessionWorker.requireOk;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.executor.internal.SessionFileTransfers.MaterializationCapacity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Resolves authorized originals and updates the local media index without saving it remotely. */
final class SessionMediaCommands {
    private static final Logger log = LoggerFactory.getLogger(SessionMediaCommands.class);
    private final SessionWorker io;
    private final SessionFileTransfers files;
    private final AuthService auth;
    private final MediaFileService media;
    private final SelectedFileSaves saves;

    SessionMediaCommands(
            SessionWorker io,
            SessionFileTransfers files,
            AuthService auth,
            MediaFileService media,
            SelectedFileSaves saves) {
        this.io = io;
        this.files = files;
        this.auth = auth;
        this.media = media;
        this.saves = saves;
    }

    BridgeReplies.Reply mediaCommand(
            ExecutionSession session, String executionId, String operation, JsonNode arguments) {
        try {
            return switch (operation) {
                case "media_list" -> listMedia(session, executionId, arguments);
                case "media_link" -> linkMedia(session, executionId, arguments);
                case "media_fetch" -> fetchMedia(session, executionId, arguments);
                default -> importMedia(session, executionId, arguments);
            };
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failedBecause("INVALID_MEDIA_REQUEST", InvalidSelectionException.reason(invalid));
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (AssetStorageException unavailable) {
            return BridgeReplies.failedBecause(
                    "MEDIA_UNAVAILABLE", unavailable.reason().name());
        } catch (ContentRepositoryException unavailable) {
            return BridgeReplies.failed("MEDIA_UNAVAILABLE");
        }
    }

    private BridgeReplies.Reply listMedia(ExecutionSession session, String executionId, JsonNode arguments) {
        var query = MediaListing.Query.parse(arguments);
        Map<String, RepositoryMediaIndex.Media> files;
        String version, source;
        if (session.fullRead) {
            if (query.commit() == null) {
                var index = this.files.captureOptional(session, executionId, RepositoryMediaIndex.PATH);
                source = "worktree";
                files = index.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                        .orElseGet(RepositoryMediaIndex::empty)
                        .files();
                version = hash(index.orElse(""));
            } else {
                var catalog =
                        media.privateCatalog(session.principal, session.key.workspace(), Optional.of(query.commit()));
                files = catalog.index().files();
                source = "repository";
                version = hash(catalog.commit());
            }
        } else {
            if (query.commit() != null) {
                throw new IllegalArgumentException("public media uses only its current projection");
            }
            files = new LinkedHashMap<>();
            session.publicExport.media().forEach((path, media) -> files.put(path, media.original()));
            source = "public-projection";
            version = session.publicExport.projectionSha256();
        }
        io.authorize(session);
        return MediaListing.page(files, query, version, source);
    }

    private BridgeReplies.Reply fetchMedia(ExecutionSession session, String executionId, JsonNode arguments) {
        var selected = BridgeArguments.mediaFetch(arguments);
        String path = selected.path();
        String destination = selected.output() == null ? path : selected.output();
        Optional<String> requested = Optional.ofNullable(selected.commit());
        MediaFileService.Download download;
        String commit = null;
        String indexSource;
        if (session.fullRead) {
            if (requested.isPresent()) {
                commit = requested.orElseThrow();
                if (!commit.matches("[0-9a-f]{40}")) {
                    throw new IllegalArgumentException("a pinned commit must be 40 lowercase hex characters");
                }
                download = media.privateDownload(session.principal, session.key.workspace(), Optional.of(commit), path);
                indexSource = "repository";
            } else {
                var source = files.captureOptional(session, executionId, RepositoryMediaIndex.PATH);
                var index = source.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                        .orElseGet(RepositoryMediaIndex::empty);
                download = media.privateOriginal(
                        session.principal,
                        session.key.workspace(),
                        path,
                        index.files().get(path));
                indexSource = "worktree";
            }
        } else {
            if (requested.isPresent()) {
                throw new IllegalArgumentException("public media uses only its current projection");
            }
            var approved = session.publicExport.media().get(path);
            if (approved == null) {
                return BridgeReplies.failed("MEDIA_UNAVAILABLE");
            }
            download = media.memberProjectionDownload(
                    session.principal,
                    session.key.workspace(),
                    approved.route(),
                    session.publicExport.sourcePaths().get(path));
            var expected = approved.original();
            var asset = download.asset();
            if (!asset.reference().assetId().equals(expected.assetId())
                    || !asset.reference().revision().equals(expected.revision())
                    || asset.size() != expected.size()
                    || !asset.mediaType().equals(expected.mediaType())) {
                return BridgeReplies.failed("MEDIA_UNAVAILABLE");
            }
            commit = session.commit;
            indexSource = "public-projection";
        }
        return installFetchedMedia(session, executionId, destination, path, indexSource, commit, download);
    }

    private BridgeReplies.Reply installFetchedMedia(
            ExecutionSession session,
            String executionId,
            String destination,
            String path,
            String indexSource,
            String commit,
            MediaFileService.Download download) {
        var asset = download.asset();
        boolean installed = files.materialize(
                session,
                executionId,
                destination,
                asset.size(),
                asset.reference().revision(),
                null,
                false,
                true,
                download::writeTo);
        if (!installed) {
            return BridgeReplies.failed(
                    "LOCAL_FILE_EXISTS", "A different local file exists; keep it or choose another --output path.");
        }
        return BridgeReplies.succeeded(new BridgeReplies.FetchResult(
                destination, path, indexSource, commit, asset.reference().revision(), asset.mediaType(), asset.size()));
    }

    private BridgeReplies.Reply importMedia(ExecutionSession session, String executionId, JsonNode arguments) {
        if (!session.fullRead) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        var selected = BridgeArguments.mediaImport(arguments);
        String file = selected.file(), path = selected.path();
        String mediaType = selected.mediaType(), key = selected.key();
        boolean replace = selected.replace();
        LocalMediaIndex local = localMediaIndex(session, executionId);
        if (local == null) {
            return missingMediaIndex();
        }
        RepositoryMediaIndex index = local.index();
        if (!availableMediaPath(session, local, path, mediaType)) {
            return BridgeReplies.failed("MEDIA_PATH_COLLIDES_WITH_GIT");
        }
        JsonNode manifest = io.request(
                session, "CAPTURE_BINARY", new WorkerRequests.CapturePath(executionId, file), Duration.ofSeconds(8));
        if (manifest.path("code").asString("").equals("CAPTURE_REJECTED")) {
            throw InvalidSelectionException.capture(manifest.path("reason").asString(""));
        }
        requireOk(manifest, session);
        var captured = WorkerResponses.read(manifest, WorkerResponses.BinaryCaptureManifest.class);
        String captureId = captured.captureId();
        var reference = new WorkerRequests.CaptureRelease(executionId, captureId);
        ManagedAsset asset;
        try {
            // One file was selected, so exactly that file must come back.
            if (captured.writes().size() != 1
                    || !captured.writes().getFirst().path().equals(file)) {
                throw new WorkerUnavailableException();
            }
            long size = captured.writes().getFirst().bytes();
            String digest = captured.writes().getFirst().sha256();
            var previous = index.files().get(path);
            if (!replace
                    && previous != null
                    && (previous.size() != size
                            || !previous.revision().equals(digest)
                            || !previous.mediaType().equals(mediaType))) {
                return BridgeReplies.failed("MEDIA_PATH_EXISTS");
            }
            asset = uploadCapturedMedia(session, executionId, captureId, path, key, mediaType, size, digest);
        } finally {
            try {
                io.request(session, "CAPTURE_RELEASE", reference, Duration.ofSeconds(3));
            } catch (RuntimeException cleanupFailure) {
                log.warn(
                        "Binary capture release was not acknowledged; command cleanup will release its staging file",
                        cleanupFailure);
            }
        }
        return indexMedia(session, executionId, path, asset, local, replace);
    }

    private ManagedAsset uploadCapturedMedia(
            ExecutionSession session,
            String executionId,
            String captureId,
            String path,
            String key,
            String mediaType,
            long size,
            String digest) {
        try (var input = new CapturedBinaryInput(size, digest, (offset, limit) -> {
            io.authorize(session);
            JsonNode chunk = io.request(
                    session,
                    "CAPTURE_READ",
                    new WorkerRequests.CaptureRead(executionId, captureId, 0, offset, limit),
                    Duration.ofSeconds(3));
            requireOk(chunk, session);
            var page = WorkerResponses.read(chunk, WorkerResponses.CaptureChunk.class);
            if (!captureId.equals(page.captureId()) || page.index() != 0 || page.offset() != offset) {
                throw new WorkerUnavailableException();
            }
            return page.decoded();
        })) {
            ManagedAsset asset = media.upload(session.principal, session.key.workspace(), key, mediaType, input);
            session.saveState.acknowledgeImport(importReceipt(path, asset, false));
            if (!input.verified()
                    || asset.size() != size
                    || !asset.reference().revision().equals(digest)
                    || !asset.mediaType().equals(mediaType)) {
                throw new WorkerUnavailableException();
            }
            return asset;
        }
    }

    private BridgeReplies.Reply linkMedia(ExecutionSession session, String executionId, JsonNode arguments) {
        if (!session.fullRead) {
            return BridgeReplies.failed("READ_ONLY_SCOPE");
        }
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        BridgeArguments.MediaLink selected = BridgeArguments.mediaLink(arguments);
        LocalMediaIndex local = localMediaIndex(session, executionId);
        if (local == null) {
            return missingMediaIndex();
        }
        ManagedAsset asset = media.describeOriginal(session.principal, session.key.workspace(), selected.reference());
        if (!availableMediaPath(session, local, selected.path(), asset.mediaType())) {
            return BridgeReplies.failed("MEDIA_PATH_COLLIDES_WITH_GIT");
        }
        session.saveState.acknowledgeImport(importReceipt(selected.path(), asset, false));
        return indexMedia(session, executionId, selected.path(), asset, local, selected.replace());
    }

    private LocalMediaIndex localMediaIndex(ExecutionSession session, String executionId) {
        Optional<String> source = files.captureOptional(session, executionId, RepositoryMediaIndex.PATH);
        if (source.isEmpty()
                && !saves.baselineFile(
                                session.principal,
                                session.key.workspace(),
                                session.saveState,
                                RepositoryMediaIndex.PATH)
                        .expectedAbsence()) {
            return null;
        }
        return new LocalMediaIndex(
                source,
                source.map(value -> RepositoryMediaIndex.parse(value.getBytes(StandardCharsets.UTF_8)))
                        .orElseGet(RepositoryMediaIndex::empty));
    }

    private static BridgeReplies.Reply missingMediaIndex() {
        return BridgeReplies.failed(
                "INDEX_MISSING",
                "Restore or intentionally recreate the local media index before importing or linking.");
    }

    private boolean availableMediaPath(ExecutionSession session, LocalMediaIndex local, String path, String mediaType) {
        var entries = new LinkedHashMap<>(local.index().files());
        entries.put(path, new RepositoryMediaIndex.Media(new UUID(0, 0), "0".repeat(64), mediaType, 1));
        new RepositoryMediaIndex(
                entries); // Validate the entire logical namespace before changing originals or the index.
        RepositoryFile existingGit =
                saves.baselineFile(session.principal, session.key.workspace(), session.saveState, path);
        return existingGit.expectedAbsence()
                || existingGit.diagnostics().stream()
                        .anyMatch(value -> value.code().equals("MANAGED_MEDIA"));
    }

    private BridgeReplies.Reply indexMedia(
            ExecutionSession session,
            String executionId,
            String path,
            ManagedAsset asset,
            LocalMediaIndex local,
            boolean replace) {
        auth.authorize(session.principal, session.key.workspace(), Capability.WRITE_PRIVATE);
        Optional<String> source = local.source();
        RepositoryMediaIndex index = local.index();
        var entries = new LinkedHashMap<>(index.files());
        var entry = new RepositoryMediaIndex.Media(
                asset.reference().assetId(), asset.reference().revision(), asset.mediaType(), asset.size());
        var previous = index.files().get(path);
        if (previous != null && !previous.equals(entry) && !replace) {
            return BridgeReplies.failedWith("MEDIA_PATH_EXISTS", session.saveState.lastImport);
        }
        entries.put(path, entry);
        byte[] changed = entry.equals(previous)
                ? source.orElseThrow().getBytes(StandardCharsets.UTF_8)
                : new RepositoryMediaIndex(entries).encode();
        String text = new String(changed, StandardCharsets.UTF_8);
        try {
            if (!files.materialize(
                    session,
                    executionId,
                    RepositoryMediaIndex.PATH,
                    changed.length,
                    hash(text),
                    source.map(SessionWorker::hash).orElse(null),
                    false,
                    false,
                    output -> output.write(changed))) {
                return BridgeReplies.failedWith("INDEX_CHANGED", session.saveState.lastImport);
            }
        } catch (MaterializationCapacity capacity) {
            return BridgeReplies.failedWith(
                    "MATERIALIZE_CAPACITY",
                    session.saveState.lastImport,
                    "Original stored; local index was not updated. Free session space and retry the same import or link.");
        }
        session.saveState.acknowledgeImport(importReceipt(path, asset, true));
        return BridgeReplies.succeeded(session.saveState.lastImport);
    }

    private static BridgeReplies.ImportReceipt importReceipt(String path, ManagedAsset asset, boolean indexed) {
        return new BridgeReplies.ImportReceipt(
                path,
                asset.reference().assetId().toString(),
                asset.reference().revision(),
                asset.mediaType(),
                asset.size(),
                true,
                indexed,
                false);
    }

    private record LocalMediaIndex(Optional<String> source, RepositoryMediaIndex index) {}
}
