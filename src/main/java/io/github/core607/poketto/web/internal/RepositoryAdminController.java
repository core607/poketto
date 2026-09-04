package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
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
@RequestMapping(path = "/api/admin/repository", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class RepositoryAdminController {
    private final AuthorizedRepositoryReader reader;
    private final RepositoryPatchService patches;
    private final WorkspaceCatalog workspaces;

    RepositoryAdminController(
            AuthorizedRepositoryReader reader, RepositoryPatchService patches, WorkspaceCatalog workspaces) {
        this.reader = reader;
        this.patches = patches;
        this.workspaces = workspaces;
    }

    @GetMapping("/tree")
    Tree tree(@AuthenticationPrincipal AuthPrincipal actor, @RequestParam(required = false) String commit) {
        var tree = reader.readTree(actor, workspaces.defaultWorkspace().id(), Optional.ofNullable(commit));
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
        var file = reader.getFile(actor, workspaces.defaultWorkspace().id(), Optional.ofNullable(commit), path);
        return new File(
                file.commit().orElse(null),
                file.path(),
                file.source().orElse(null),
                file.revision().map(DocumentRevision::value).orElse(null),
                file.expectedAbsence(),
                file.diagnostics());
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
                actor,
                workspaces.defaultWorkspace().id(),
                Optional.ofNullable(commit),
                query,
                tag,
                from,
                to,
                offset,
                limit);
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
        var result = patches.apply(actor, workspaces.defaultWorkspace().id(), patch);
        Map<String, String> revisions = new LinkedHashMap<>();
        result.revisions()
                .forEach((path, revision) -> revisions.put(
                        path, revision.map(DocumentRevision::value).orElse(null)));
        return new PatchResult(result.commit(), result.committed(), result.snapshotUpdated(), revisions);
    }

    record Entry(String path, String title) {}

    record Tree(String commit, List<Entry> entries, List<RepositoryDiagnostic> diagnostics) {}

    record File(
            String commit,
            String path,
            String source,
            String revision,
            boolean expectedAbsence,
            List<RepositoryDiagnostic> diagnostics) {}

    record Change(String path, boolean expectedAbsence, String expectedRevision, String content) {}

    record PatchRequest(String baseCommit, List<Change> changes) {}

    record PatchResult(String commit, boolean committed, boolean snapshotUpdated, Map<String, String> revisions) {}
}
