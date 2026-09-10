package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** Synthetic Git authority and real projection service for the native worker acceptance. */
public final class PublicExecutionNativeFixture {
    private final RemoteRepositoryFixture repository;
    private final WorkspaceId workspace;
    private final JGitPublicContentSnapshots snapshots;
    private final RepositorySnapshotExports exports;
    private final String sourceCommit;
    private final Path fixtureRoot;
    private io.github.core607.poketto.assets.ManagedBlobStore originals;
    private final java.util.concurrent.atomic.AtomicBoolean offline = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger pushes = new java.util.concurrent.atomic.AtomicInteger();

    public PublicExecutionNativeFixture(Path root, Path staging, AuthService auth, WorkspaceId workspace)
            throws Exception {
        this(root, staging, auth, workspace, false);
    }

    public PublicExecutionNativeFixture(
            Path root, Path staging, AuthService auth, WorkspaceId workspace, boolean loseFirstReply) throws Exception {
        this.workspace = workspace;
        this.fixtureRoot = root;
        var delegate = new JGitRemoteGitTransport();
        repository = new RemoteRepositoryFixture(root, new RemoteGitTransport() {
            @Override
            public org.eclipse.jgit.lib.ObjectId fetchMain(
                    org.eclipse.jgit.lib.Repository repo, RepositoryBinding binding) {
                if (offline.get()) throw new RemoteGitTransportException("synthetic lost-response outage");
                return delegate.fetchMain(repo, binding);
            }

            @Override
            public PushStatus pushMain(
                    org.eclipse.jgit.lib.Repository repo,
                    RepositoryBinding binding,
                    org.eclipse.jgit.lib.ObjectId expected,
                    org.eclipse.jgit.lib.ObjectId candidate) {
                var result = delegate.pushMain(repo, binding, expected, candidate);
                if (pushes.incrementAndGet() == 1 && loseFirstReply) {
                    offline.set(true);
                    throw new RemoteGitTransportException("synthetic lost-response outage");
                }
                return result;
            }
        });
        repository.commitRemote(workspace, Map.of("private/secret.md", text("historic-secret-needle")));
        sourceCommit = repository
                .commitRemote(
                        workspace,
                        Map.of(
                                RepositoryPublishingPolicy.PATH,
                                text("enabled: true\nmode: public-by-default\n"),
                                "article.md",
                                text("---\ntitle: Public native article\nsecret: metadata-secret-needle\n---\n"
                                        + "public-native-body\n\n<!-- comment-secret-needle -->\n"),
                                "private/secret.md",
                                text("current-secret-needle"),
                                "AGENTS.md",
                                text("operator-secret-needle")))
                .name();
        snapshots = new JGitPublicContentSnapshots(repository.authority(), Clock.systemUTC(), Duration.ofHours(1));
        snapshots.refresh(workspace);
        exports = new JGitRepositorySnapshotExports(
                repository.authority(), auth, staging, 1024 * 1024, Duration.ofSeconds(10), snapshots);
    }

    public RepositorySnapshotExports exports() {
        return exports;
    }

    public io.github.core607.poketto.assets.MediaFileService media(AuthService auth) {
        return new io.github.core607.poketto.assets.MediaFileService(
                auth, new JGitRepositoryBlobReader(repository.authority()), snapshots, () -> {
                    if (originals == null)
                        originals = io.github.core607.poketto.assets.ManagedBlobStore.local(
                                fixtureRoot.resolve("originals"));
                    return originals;
                });
    }

    public String seedMedia(
            AuthService auth,
            io.github.core607.poketto.auth.AuthPrincipal actor,
            byte[] publicBytes,
            byte[] privateBytes) {
        var files = new java.util.LinkedHashMap<String, io.github.core607.poketto.content.RepositoryMediaIndex.Media>();
        for (var entry : Map.of("public/manual.pdf", publicBytes, "private/manual.pdf", privateBytes)
                .entrySet()) {
            var asset = media(auth)
                    .upload(
                            actor,
                            workspace,
                            "native-" + java.util.UUID.randomUUID(),
                            "application/pdf",
                            new java.io.ByteArrayInputStream(entry.getValue()));
            files.put(
                    entry.getKey(),
                    new io.github.core607.poketto.content.RepositoryMediaIndex.Media(
                            asset.reference().assetId(),
                            asset.reference().revision(),
                            asset.mediaType(),
                            asset.size()));
        }
        var reader = reader(auth);
        var index = reader.getFile(
                actor,
                workspace,
                java.util.Optional.empty(),
                io.github.core607.poketto.content.RepositoryMediaIndex.PATH);
        var article = reader.getFile(actor, workspace, index.commit(), "article.md");
        return patches(auth)
                .apply(
                        actor,
                        workspace,
                        new io.github.core607.poketto.content.RepositoryPatch(
                                index.commit(),
                                java.util.List.of(
                                        new io.github.core607.poketto.content.RepositoryTextChange(
                                                index.path(),
                                                index.expectedAbsence(),
                                                index.revision(),
                                                java.util.Optional.of(new String(
                                                        new io.github.core607.poketto.content.RepositoryMediaIndex(
                                                                        files)
                                                                .encode(),
                                                        StandardCharsets.UTF_8))),
                                        new io.github.core607.poketto.content.RepositoryTextChange(
                                                article.path(),
                                                false,
                                                article.revision(),
                                                java.util.Optional.of(
                                                        "# Media fixture\n[Manual](public/manual.pdf)\n[Hidden](private/manual.pdf)\n")))))
                .commit();
    }

    public void restoreTransport() {
        offline.set(false);
    }

    public int pushes() {
        return pushes.get();
    }

    public io.github.core607.poketto.content.AuthorizedRepositoryReader reader(AuthService auth) {
        return new io.github.core607.poketto.content.AuthorizedRepositoryReader(
                auth, new JGitRepositoryContentReader(repository.authority()));
    }

    public io.github.core607.poketto.content.RepositoryPatchService patches(AuthService auth) {
        return new JGitRepositoryPatchService(
                repository.authority(),
                auth,
                Clock.systemUTC(),
                snapshots::installAcknowledged,
                snapshots::closePublication,
                org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryMediaValidator.class));
    }

    public void competingWrite(AuthService auth, io.github.core607.poketto.auth.AuthPrincipal actor) {
        var current = reader(auth).getFile(actor, workspace, java.util.Optional.empty(), "AGENTS.md");
        patches(auth)
                .apply(
                        actor,
                        workspace,
                        new io.github.core607.poketto.content.RepositoryPatch(
                                current.commit(),
                                java.util.List.of(new io.github.core607.poketto.content.RepositoryTextChange(
                                        "AGENTS.md",
                                        false,
                                        current.revision(),
                                        java.util.Optional.of("externally-updated-guide")))));
    }

    public String sourceCommit() {
        return sourceCommit;
    }

    public void withdraw() throws Exception {
        repository.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, text("enabled: false\nmode: public-by-default\n")));
        snapshots.refresh(workspace);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
