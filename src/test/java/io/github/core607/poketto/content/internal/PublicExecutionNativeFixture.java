package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.ManagedOriginalTransfers;
import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaValidator;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.mockito.Mockito;

/** Synthetic Git authority and real projection service for the native worker acceptance. */
public final class PublicExecutionNativeFixture implements AutoCloseable {
    private final RemoteRepositoryFixture repository;
    private final WorkspaceId workspace;
    private final JGitPublicContentSnapshots snapshots;
    private final RepositorySnapshotExports exports;
    private final String sourceCommit;
    private final Path fixtureRoot;
    private ManagedBlobStore originals;
    private LocalPortableContentExports packages;
    private final AtomicBoolean offline = new AtomicBoolean();
    private final AtomicInteger pushes = new AtomicInteger();

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
            public ObjectId fetchMain(Repository repo, RepositoryBinding binding) {
                if (offline.get()) {
                    throw new RemoteGitTransportException("synthetic lost-response outage");
                }
                return delegate.fetchMain(repo, binding);
            }

            @Override
            public PushStatus pushMain(
                    Repository repo, RepositoryBinding binding, ObjectId expected, ObjectId candidate) {
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
                                text("enabled: true\nmode: public-root\n"),
                                "public/article.md",
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

    public MediaFileService media(AuthService auth) {
        return new MediaFileService(auth, new JGitRepositoryBlobReader(repository.authority()), snapshots, () -> {
            if (originals == null) {
                originals = ManagedBlobStore.local(fixtureRoot.resolve("originals"));
            }
            return originals;
        });
    }

    public PortableContentExports packages(AuthService auth) {
        if (packages == null) {
            if (originals == null) {
                originals = ManagedBlobStore.local(fixtureRoot.resolve("originals"));
            }
            var planner = new PortableContentPlanner(
                    auth,
                    new JGitRepositoryContentReader(repository.authority()),
                    new JGitRepositoryBlobReader(repository.authority()),
                    snapshots,
                    new ManagedOriginalTransfers(() -> originals));
            packages = new LocalPortableContentExports(
                    auth,
                    planner,
                    fixtureRoot.resolve("packages"),
                    Clock.systemUTC(),
                    new LocalPortableContentExports.Limits(
                            16 * 1024 * 1024,
                            32 * 1024 * 1024,
                            16 * 1024 * 1024,
                            4,
                            Duration.ofMinutes(2),
                            Duration.ofSeconds(20)));
        }
        return packages;
    }

    public long retainedPackages() throws IOException {
        try (var paths = Files.walk(fixtureRoot.resolve("packages"))) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .count();
        }
    }

    @Override
    public void close() {
        if (packages != null) {
            packages.close();
        }
    }

    public String seedMedia(AuthService auth, AuthPrincipal actor, byte[] publicBytes, byte[] privateBytes) {
        var files = new LinkedHashMap<String, RepositoryMediaIndex.Media>();
        for (var entry : Map.of("public/manual.pdf", publicBytes, "private/manual.pdf", privateBytes)
                .entrySet()) {
            var asset = media(auth)
                    .upload(
                            actor,
                            workspace,
                            "native-" + UUID.randomUUID(),
                            "application/pdf",
                            new ByteArrayInputStream(entry.getValue()));
            files.put(
                    entry.getKey(),
                    new RepositoryMediaIndex.Media(
                            asset.reference().assetId(),
                            asset.reference().revision(),
                            asset.mediaType(),
                            asset.size()));
        }
        var reader = reader(auth);
        var index = reader.getFile(actor, workspace, Optional.empty(), RepositoryMediaIndex.PATH);
        var article = reader.getFile(actor, workspace, index.commit(), "public/article.md");
        return patches(auth)
                .apply(
                        actor,
                        workspace,
                        new RepositoryPatch(
                                index.commit(),
                                List.of(
                                        new RepositoryTextChange(
                                                index.path(),
                                                index.expectedAbsence(),
                                                index.revision(),
                                                Optional.of(new String(
                                                        new RepositoryMediaIndex(files).encode(),
                                                        StandardCharsets.UTF_8))),
                                        new RepositoryTextChange(
                                                article.path(),
                                                false,
                                                article.revision(),
                                                Optional.of(
                                                        "# Media fixture\n[Manual](manual.pdf)\n[Hidden](../private/manual.pdf)\n")))))
                .commit();
    }

    public void restoreTransport() {
        offline.set(false);
    }

    public int pushes() {
        return pushes.get();
    }

    public AuthorizedRepositoryReader reader(AuthService auth) {
        return new AuthorizedRepositoryReader(auth, new JGitRepositoryContentReader(repository.authority()));
    }

    public RepositoryPatchService patches(AuthService auth) {
        return writeService(auth);
    }

    public RepositoryMoveService moves(AuthService auth) {
        return writeService(auth);
    }

    private JGitRepositoryPatchService writeService(AuthService auth) {
        return new JGitRepositoryPatchService(
                repository.authority(),
                auth,
                Clock.systemUTC(),
                snapshots::installAcknowledged,
                snapshots::closePublication,
                Mockito.mock(RepositoryMediaValidator.class));
    }

    public void competingWrite(AuthService auth, AuthPrincipal actor) {
        var current = reader(auth).getFile(actor, workspace, Optional.empty(), "AGENTS.md");
        patches(auth)
                .apply(
                        actor,
                        workspace,
                        new RepositoryPatch(
                                current.commit(),
                                List.of(new RepositoryTextChange(
                                        "AGENTS.md",
                                        false,
                                        current.revision(),
                                        Optional.of("externally-updated-guide")))));
    }

    public String sourceCommit() {
        return sourceCommit;
    }

    public void withdraw() throws Exception {
        repository.commitRemote(
                workspace, Map.of(RepositoryPublishingPolicy.PATH, text("enabled: false\nmode: public-root\n")));
        snapshots.refresh(workspace);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
