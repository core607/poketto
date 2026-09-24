package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.content.ReviewedBodyEdits;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

final class RepositoryReviewedBodyEdits implements ReviewedBodyEdits {
    private static final int ATTEMPTS = 3;
    private static final RepositoryMarkdownParser PARSER = new RepositoryMarkdownParser();
    private final AuthService auth;
    private final PublicContentSnapshots snapshots;
    private final AuthorizedRepositoryReader reader;
    private final RepositoryPatchService patches;

    RepositoryReviewedBodyEdits(
            AuthService auth,
            PublicContentSnapshots snapshots,
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches) {
        this.auth = auth;
        this.snapshots = snapshots;
        this.reader = reader;
        this.patches = patches;
    }

    @Override
    public Outcome replaceBody(
            AuthPrincipal reviewer,
            WorkspaceId workspace,
            String route,
            String baseDigest,
            String body,
            Optional<WritePrincipal> suggestedBy) {
        auth.authorize(reviewer, workspace, Capability.PUBLISH);
        String proposed = digest(body);
        RepositoryConflictException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<String> path = snapshots.current(workspace).articles().stream()
                    .filter(article -> article.route().equals(route))
                    .map(PublicArticle::repositoryPath)
                    .findFirst();
            if (path.isEmpty()) {
                return new Outcome(Result.STALE, Optional.empty());
            }
            var file = reader.getFile(reviewer, workspace, Optional.empty(), path.get());
            if (file.source().isEmpty() || file.revision().isEmpty()) {
                return new Outcome(Result.STALE, Optional.empty());
            }
            String source = file.source().get();
            String current;
            try {
                current = PARSER.parse(path.get(), source).body();
            } catch (IllegalArgumentException invalid) {
                return new Outcome(Result.STALE, Optional.empty());
            }
            if (digest(current).equals(proposed)) {
                return new Outcome(Result.ALREADY_APPLIED, Optional.empty());
            }
            if (!digest(current).equals(baseDigest)) {
                return new Outcome(Result.STALE, Optional.empty());
            }
            // The parser's body is always a suffix of the source, so everything before it is kept byte for byte.
            String text = source.substring(0, source.length() - current.length()) + body;
            try {
                var written = patches.apply(
                        reviewer,
                        workspace,
                        new RepositoryPatch(
                                file.commit(),
                                List.of(new RepositoryTextChange(
                                        path.get(), false, file.revision(), Optional.of(text))),
                                suggestedBy));
                return new Outcome(Result.APPLIED, Optional.of(written.commit()));
            } catch (RepositoryConflictException moved) {
                last = moved;
            }
        }
        throw last;
    }

    static String digest(String body) {
        return DocumentRevision.sha256(body.getBytes(StandardCharsets.UTF_8)).value();
    }
}
