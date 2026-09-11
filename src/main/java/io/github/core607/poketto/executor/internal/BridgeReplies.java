package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What a {@code poketto} command receives. The CLI checks only that {@code ok} is a boolean and
 * prints the rest verbatim, so this JSON is read by the agent inside the sandbox: a field renamed
 * here changes what that agent sees, and the worker reference documents the codes.
 *
 * <p>Absent is not the same as null on this wire. {@link Reply} drops its unset parts, because a
 * reply that carries no message must not show an empty one, while {@link MediaPage} keeps an unset
 * {@code nextOffset} as an explicit null, because the CLI reads that null as end of listing.
 */
final class BridgeReplies {

    private BridgeReplies() {}

    /** Anything a session can put where a reply belongs, including the absence of one. */
    sealed interface Message permits Absent, Reply {}

    /** No reply yet. A session reports its last save and last import as {@code {}} until one exists. */
    record Absent() implements Message {}

    /**
     * One reply. {@code ok} and {@code code} are the only parts the CLI contract fixes; the rest
     * are set by the factories below, and an unset part is omitted rather than sent as null.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Reply(
            boolean ok,
            String code,
            Object result,
            Object artifact,
            Boolean removed,
            String reason,
            String indexVersion,
            String message)
            implements Message {
        Reply {
            require(ok || code != null, "code", "must name why a reply failed");
        }
    }

    static Reply failed(String code) {
        return new Reply(false, code, null, null, null, null, null, null);
    }

    static Reply failed(String code, String message) {
        return new Reply(false, code, null, null, null, null, null, message);
    }

    /** A storage failure names its reason separately so the agent can distinguish retry from stop. */
    static Reply failedBecause(String code, String reason) {
        return new Reply(false, code, null, null, null, reason, null, null);
    }

    /** A failed write still returns its receipt, so a retry can tell what already happened. */
    static Reply failedWith(String code, Object result) {
        return new Reply(false, code, result, null, null, null, null, null);
    }

    static Reply failedWith(String code, Object result, String message) {
        return new Reply(false, code, result, null, null, null, null, message);
    }

    /** The listing the caller asked for no longer exists; the current version lets it restart. */
    static Reply staleIndex(String indexVersion) {
        return new Reply(false, "MEDIA_INDEX_CHANGED", null, null, null, null, indexVersion, null);
    }

    static Reply succeeded(Object result) {
        return new Reply(true, null, result, null, null, null, null, null);
    }

    /**
     * A synchronization always returns its merged file; whether that counts as success is the
     * caller's decision, so the outcome and the code travel together.
     */
    static Reply outcome(boolean ok, String code, Object result) {
        return new Reply(ok, code, result, null, null, null, null, null);
    }

    static Reply artifact(Object artifact) {
        return new Reply(true, null, null, artifact, null, null, null, null);
    }

    static Reply removed() {
        return new Reply(true, null, null, null, true, null, null, null);
    }

    /** The session's own view of where its writes stand, returned by {@code poketto status}. */
    record Status(
            String scope,
            String baseCommit,
            boolean writeOutcomeUnknown,
            boolean movePending,
            Object move,
            Object lastSave,
            Object lastImport) {}

    record SaveResult(
            String commit, boolean committed, boolean snapshotUpdated, List<String> paths, boolean recovered) {}

    /** Nothing was retained to recover, so the command succeeds without touching the repository. */
    record Recovery(boolean recoveryNeeded) {}

    record MoveInstalled(
            String commit,
            boolean committed,
            boolean snapshotUpdated,
            boolean worktreeUpdated,
            String source,
            String destination) {}

    record MoveSkipped(String commit, boolean committed, boolean worktreeUpdated, boolean localInstallationSkipped) {}

    /**
     * A move whose local installation has not finished. The commit is null until the remote write
     * is confirmed and stays in the frame as null, because its absence would read as "no move".
     */
    record MovePending(boolean committed, String commit, boolean worktreeUpdated, String source, String destination) {}

    record SyncResult(String path, String baseCommit, boolean saved, boolean conflicted) {}

    /** An original written into the worktree. The commit is absent for a listing of unsaved work. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FetchResult(
            String path,
            String sourcePath,
            String indexSource,
            String commit,
            String sha256,
            String mediaType,
            long bytes) {}

    record ImportReceipt(
            String path,
            String assetId,
            String sha256,
            String mediaType,
            long bytes,
            boolean originalStored,
            boolean indexUpdated,
            boolean saved) {}

    record ExportResult(String path, long bytes, String sha256, String scope, boolean saved) {}

    /**
     * One page of the media index. The commit is absent unless the caller pinned one, while
     * {@code nextOffset} is always present: the CLI reads its null as end of listing, so dropping
     * it would turn a finished page into an unfinished one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MediaPage(
            String indexSource,
            String indexVersion,
            String commit,
            List<MediaItem> items,
            int total,
            int offset,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer nextOffset) {}

    record MediaItem(String path, String mediaType, long size) {}

    record ArtifactMetadata(
            String artifactId,
            String name,
            String mediaType,
            long bytes,
            String sha256,
            boolean truncated,
            int expiresInSeconds) {}
}
