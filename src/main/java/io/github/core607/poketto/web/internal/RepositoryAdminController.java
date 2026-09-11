package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryDirectoryPage;
import io.github.core607.poketto.content.RepositoryMoveRequest;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/admin/workspaces/{workspaceId}/repository", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class RepositoryAdminController {
    private final AuthorizedRepositoryReader reader;
    private final RepositoryPatchService patches;
    private final RepositoryMoveService moves;
    private final BrowserWorkspace workspaces;

    RepositoryAdminController(
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches,
            RepositoryMoveService moves,
            BrowserWorkspace workspaces) {
        this.reader = reader;
        this.patches = patches;
        this.moves = moves;
        this.workspaces = workspaces;
    }

    @GetMapping("/directory")
    Directory directory(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(required = false) String commit,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        var page = reader.listDirectory(actor, workspaces.selected(), Optional.ofNullable(commit), path, offset, limit);
        return new Directory(
                page.commit().orElse(null), page.path(), page.expectedAbsence(), page.entries(), page.nextOffset());
    }

    @PostMapping("/move")
    PatchResult move(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody MoveRequest request) {
        if (request.source() == null || request.destination() == null)
            throw new IllegalArgumentException("move source and destination are required");
        var move = new RepositoryMoveRequest(request.baseCommit(), request.source(), request.destination());
        return result(moves.move(actor, workspaces.selected(), move));
    }

    @GetMapping("/tree")
    Tree tree(@AuthenticationPrincipal AuthPrincipal actor, @RequestParam(required = false) String commit) {
        var tree = reader.readTree(actor, workspaces.selected(), Optional.ofNullable(commit));
        Map<String, Entry> entries = new TreeMap<>();
        tree.documents()
                .forEach(document -> entries.put(
                        document.file().path(), new Entry(document.file().path(), document.title())));
        tree.diagnostics()
                .forEach(diagnostic ->
                        entries.putIfAbsent(diagnostic.path(), new Entry(diagnostic.path(), diagnostic.path())));
        return new Tree(tree.commit().orElse(null), List.copyOf(entries.values()), tree.diagnostics());
    }

    @GetMapping("/file")
    File file(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam String path,
            @RequestParam(required = false) String commit) {
        var file = reader.getFile(actor, workspaces.selected(), Optional.ofNullable(commit), path);
        return new File(
                file.commit().orElse(null),
                file.path(),
                file.source().orElse(null),
                file.revision().map(DocumentRevision::value).orElse(null),
                file.expectedAbsence(),
                file.diagnostics(),
                file.publicScope());
    }

    @GetMapping("/search")
    AuthorizedRepositoryReader.SearchPage search(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(required = false) String commit,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String tag,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return reader.search(
                actor, workspaces.selected(), Optional.ofNullable(commit), query, tag, from, to, offset, limit);
    }

    @PostMapping("/patch")
    PatchResult patch(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody PatchRequest request) {
        if (request.changes() == null
                || request.changes().stream().anyMatch(change -> change == null || change.path() == null)) {
            throw new IllegalArgumentException("patch changes and paths are required");
        }
        var patch = new RepositoryPatch(
                Optional.ofNullable(request.baseCommit()),
                request.changes().stream()
                        .map(change -> new RepositoryTextChange(
                                change.path(),
                                change.expectedAbsence(),
                                Optional.ofNullable(change.expectedRevision()).map(DocumentRevision::new),
                                Optional.ofNullable(change.content())))
                        .toList());
        return result(patches.apply(actor, workspaces.selected(), patch));
    }

    private static PatchResult result(RepositoryPatchResult result) {
        Map<String, String> revisions = new LinkedHashMap<>();
        result.revisions()
                .forEach((path, revision) -> revisions.put(
                        path, revision.map(DocumentRevision::value).orElse(null)));
        return new PatchResult(result.commit(), result.committed(), result.snapshotUpdated(), revisions);
    }

    record Entry(String path, String title) {}

    record Directory(
            String commit,
            String path,
            boolean expectedAbsence,
            List<RepositoryDirectoryPage.Entry> entries,
            Integer nextOffset) {}

    record MoveRequest(String baseCommit, String source, String destination) {}

    record Tree(String commit, List<Entry> entries, List<RepositoryDiagnostic> diagnostics) {}

    record File(
            String commit,
            String path,
            String source,
            String revision,
            boolean expectedAbsence,
            List<RepositoryDiagnostic> diagnostics,
            boolean publicScope) {}

    record Change(String path, boolean expectedAbsence, String expectedRevision, String content) {}

    record PatchRequest(String baseCommit, List<Change> changes) {}

    record PatchResult(String commit, boolean committed, boolean snapshotUpdated, Map<String, String> revisions) {}
}
