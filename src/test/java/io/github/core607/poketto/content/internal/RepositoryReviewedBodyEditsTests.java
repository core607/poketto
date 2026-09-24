package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.PrincipalType;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.ReviewedBodyEdits.Result;
import io.github.core607.poketto.content.WritePrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReviewedBodyEditsTests {
    private static final String POLICY = "enabled: true\nmode: public-root\n";
    // A byte-order mark and CRLF frontmatter prove that everything before the body is kept verbatim.
    private static final String HEAD = (char) 0xFEFF + "---\r\ntitle: Essay\r\ntags: [river]\r\n---\r\n";
    private static final String BODY = "# Essay\n\nThe river is 30 km long.\n";
    private static final String PROPOSED = "# Essay\n\nThe river is 32 km long.\n";
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal reviewer = mock(AuthPrincipal.class);
    private final AuthService auth = mock(AuthService.class, RETURNS_DEEP_STUBS);
    private final Optional<WritePrincipal> reader =
            Optional.of(new WritePrincipal(PrincipalType.ACCOUNT, "0d9b7c1e-8f7a-4a52-9d1e-3c2f4b5a6d7e"));

    @TempDir
    Path directory;

    @Test
    void theProposedBodyReplacesOnlyTheBodyAndCreditsTheReader() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, files(BODY));
        var edits = edits(fixture);

        var outcome = edits.replaceBody(reviewer, workspace, "/essay", digest(BODY), PROPOSED, reader);
        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(outcome.commit()).contains(fixture.remoteHead(workspace).name());
        assertThat(file(fixture)).isEqualTo(HEAD + PROPOSED);
        assertThat(message(fixture))
                .endsWith("Poketto-Principal: account:bf562fc1-f15b-4adb-80d2-b0fdab1a568d\n"
                        + "Poketto-Suggested-By: account:0d9b7c1e-8f7a-4a52-9d1e-3c2f4b5a6d7e\n");

        var head = fixture.remoteHead(workspace);
        var repeated = edits.replaceBody(reviewer, workspace, "/essay", digest(BODY), PROPOSED, reader);
        assertThat(repeated.result()).isEqualTo(Result.ALREADY_APPLIED);
        assertThat(repeated.commit()).contains(head.name());
        assertThat(fixture.remoteHead(workspace)).isEqualTo(head);
    }

    @Test
    void aChangedBodyOrAnUnservedRouteIsStaleAndWritesNothing() throws Exception {
        var fixture = new RemoteRepositoryFixture(directory);
        fixture.commitRemote(workspace, files(BODY));
        var edits = edits(fixture);
        var changed = fixture.commitRemote(workspace, files("# Essay\n\nRewritten by the author.\n"));

        assertThat(edits.replaceBody(reviewer, workspace, "/essay", digest(BODY), PROPOSED, reader)
                        .result())
                .isEqualTo(Result.STALE);
        assertThat(edits.replaceBody(reviewer, workspace, "/missing", digest(BODY), PROPOSED, reader)
                        .result())
                .isEqualTo(Result.STALE);
        assertThat(fixture.remoteHead(workspace)).isEqualTo(changed);
    }

    private RepositoryReviewedBodyEdits edits(RemoteRepositoryFixture fixture) {
        when(reviewer.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(reviewer.subjectId()).thenReturn(UUID.fromString("bf562fc1-f15b-4adb-80d2-b0fdab1a568d"));
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var snapshots = new JGitPublicContentSnapshots(fixture.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        var patches = new JGitRepositoryPatchService(
                fixture.authority(),
                auth,
                Clock.systemUTC(),
                (id, snapshot) -> {},
                (id, snapshot) -> {},
                mock(RepositoryMediaValidator.class));
        var reader = new AuthorizedRepositoryReader(auth, new JGitRepositoryContentReader(fixture.authority()));
        return new RepositoryReviewedBodyEdits(auth, snapshots, reader, patches);
    }

    private static Map<String, byte[]> files(String body) {
        return Map.of(
                RepositoryPublishingPolicy.PATH,
                POLICY.getBytes(StandardCharsets.UTF_8),
                "public/essay.md",
                (HEAD + body).getBytes(StandardCharsets.UTF_8));
    }

    private String file(RemoteRepositoryFixture fixture) throws Exception {
        try (var remote = fixture.openRemote(workspace);
                var walk = new RevWalk(remote);
                var entry = TreeWalk.forPath(
                        remote,
                        "public/essay.md",
                        walk.parseCommit(fixture.remoteHead(workspace)).getTree())) {
            return new String(remote.open(entry.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }

    private String message(RemoteRepositoryFixture fixture) throws Exception {
        try (var remote = fixture.openRemote(workspace);
                var walk = new RevWalk(remote)) {
            return walk.parseCommit(fixture.remoteHead(workspace)).getFullMessage();
        }
    }

    private static String digest(String body) {
        return RepositoryReviewedBodyEdits.digest(body);
    }
}
