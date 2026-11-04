package io.github.core607.poketto.executor.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** Publishes the real full and public content exports to an isolated service for independent sandbox inspection. */
public final class NativeCommitTemplateProbe {
    private NativeCommitTemplateProbe() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        var auth = mock(AuthService.class);
        var actor = mock(AuthPrincipal.class);
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        var allowed = new WorkspaceAccess(workspace, actor, MembershipRole.OWNER, Set.of(Capability.values()));
        when(auth.authorize(any(), any(), any(Capability[].class))).thenReturn(allowed);
        when(auth.authorize(any(), any())).thenReturn(allowed);
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        SandcrabClient client = ExecutorConfiguration.sandcrabClient(Path.of(args[0]));
        var info = client.info();
        try (var fixture = new PublicExecutionNativeFixture(root, root.resolve("bundles"), auth, workspace);
                var transfers = new NativeTransferStore(root.resolve("transfers"), 32 * 1024 * 1024L, 8)) {
            var builder = new NativeCommitTemplate(client, transfers, new NativeRepositoryProfiles("python", "python"));
            var exports = fixture.exports();
            var full = materialize(
                    builder,
                    client,
                    info,
                    exports,
                    exports.create(actor, workspace, Optional.empty()),
                    actor,
                    workspace);
            var repeat = materialize(
                    builder,
                    client,
                    info,
                    exports,
                    exports.create(actor, workspace, Optional.empty()),
                    actor,
                    workspace);
            var publication = exports.createPublic(actor, workspace);
            var projection = materialize(builder, client, info, exports, publication.export(), actor, workspace);
            if (!full.equals(repeat) || full.equals(projection)) {
                throw new AssertionError(
                        "Commit aliases or the projected template identity differ from their expected roles");
            }
            System.out.println("TEMPLATES "
                    + new JsonMapper()
                            .writeValueAsString(Map.of(
                                    "full",
                                    full,
                                    "repeat",
                                    repeat,
                                    "public",
                                    projection,
                                    "commit",
                                    fixture.sourceCommit(),
                                    "projectionCommit",
                                    publication.export().commit())));
        }
    }

    private static String materialize(
            NativeCommitTemplate builder,
            SandcrabClient client,
            SandcrabProtocol.Info info,
            RepositorySnapshotExports exports,
            RepositorySnapshotExports.Export export,
            AuthPrincipal actor,
            WorkspaceId workspace) {
        var result = new AtomicReference<String>();
        try {
            exports.withBundle(
                    actor,
                    workspace,
                    export,
                    path -> builder.withArchive(
                            info,
                            export,
                            path,
                            () -> {},
                            file -> result.set(client.templates()
                                    .publish("python", file, () -> {})
                                    .id())));
            return result.get();
        } finally {
            exports.release(export.exportId());
        }
    }
}
