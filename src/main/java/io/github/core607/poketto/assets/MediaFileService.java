package io.github.core607.poketto.assets;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Original-file transfers. Declared types never authorize inline rendering or publication. */
public final class MediaFileService {
    private static final int AUTHORIZATION_BYTES = 256 * 1024;
    private final AuthService auth;
    private final RepositoryBlobReader repository;
    private final PublicContentSnapshots snapshots;
    private final Supplier<ManagedBlobStore> originals;
    private final Map<WorkspaceId, Integer> active = new HashMap<>();
    private final java.util.Set<WorkspaceId> publicTransfers = new java.util.HashSet<>();
    private int total;

    public MediaFileService(
            AuthService auth,
            RepositoryBlobReader repository,
            PublicContentSnapshots snapshots,
            Supplier<ManagedBlobStore> originals) {
        this.auth = auth;
        this.repository = repository;
        this.snapshots = snapshots;
        this.originals = originals;
    }

    /** Acknowledges durable bytes only; an authorized index/text save is a separate operation. */
    public ManagedAsset upload(
            AuthPrincipal actor, WorkspaceId workspace, String key, String mediaType, InputStream input) {
        Runnable check = () -> auth.authorize(actor, workspace, Capability.WRITE_PRIVATE);
        check.run();
        try (var admission = admit(workspace, false)) {
            var guarded = new FilterInputStream(input) {
                long allowance;

                private void authorize() {
                    if (allowance <= 0) {
                        check.run();
                        allowance = AUTHORIZATION_BYTES;
                    }
                }

                @Override
                public int read() throws IOException {
                    authorize();
                    int value = in.read();
                    if (value >= 0) allowance--;
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    authorize();
                    int count = in.read(bytes, offset, (int) Math.min(length, allowance));
                    if (count > 0) allowance -= count;
                    return count;
                }
            };
            ManagedAsset asset = originals.get().uploadFile(workspace, key, mediaType, guarded);
            check.run();
            return asset;
        } catch (RuntimeException failure) {
            throw checkedFailure(check, failure);
        }
    }

    /** Authorized exact-commit logical metadata; original availability is checked only when fetched. */
    public io.github.core607.poketto.content.RepositoryMediaSnapshot privateCatalog(
            AuthPrincipal actor, WorkspaceId workspace, Optional<String> requested) {
        Runnable check = () -> auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        check.run();
        try (var admission = admit(workspace, false)) {
            String commit = repository.selectCommit(workspace, requested).orElseThrow(MediaFileService::missing);
            var catalog = repository.media(workspace, commit);
            check.run();
            return catalog;
        } catch (RuntimeException failure) {
            throw checkedFailure(check, failure);
        }
    }

    public Download privateDownload(
            AuthPrincipal actor, WorkspaceId workspace, Optional<String> requested, String path) {
        Runnable check = () -> auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        check.run();
        try (var admission = admit(workspace, false)) {
            String commit = repository.selectCommit(workspace, requested).orElseThrow(MediaFileService::missing);
            var catalog = repository.media(workspace, commit);
            ManagedAsset asset = resolve(workspace, catalog.index().files().get(path));
            check.run();
            return new Download(workspace, path, asset, check, false);
        } catch (RuntimeException failure) {
            throw checkedFailure(check, failure);
        }
    }

    /** Full-read callers may use a mutable local index; its entry never grants access outside this workspace. */
    public Download privateOriginal(
            AuthPrincipal actor, WorkspaceId workspace, String path, RepositoryMediaIndex.Media entry) {
        Runnable check = () -> auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        check.run();
        try (var admission = admit(workspace, false)) {
            ManagedAsset asset = resolve(workspace, entry);
            check.run();
            return new Download(workspace, path, asset, check, false);
        } catch (RuntimeException failure) {
            throw checkedFailure(check, failure);
        }
    }

    public Download publicDownload(WorkspaceId workspace, String commit, String route, String path) {
        PublicArticle article = snapshots.withCurrent(workspace, snapshot -> publicArticle(snapshot, commit, route));
        Runnable check = () -> snapshots.withCurrent(workspace, snapshot -> {
            if (!publicArticle(snapshot, commit, route).equals(article)) throw missing();
            return null;
        });
        try (var admission = admit(workspace, true)) {
            var catalog = repository.media(workspace, commit);
            if (!catalog.publicPaths().contains(path) || !references(article, path)) throw missing();
            ManagedAsset asset = resolve(workspace, catalog.index().files().get(path));
            check.run();
            return new Download(workspace, path, asset, check, true);
        } catch (RuntimeException failure) {
            throw checkedFailure(check, failure);
        }
    }

    /** Resolves only a currently referenced public original; callers must not return the source commit as public history. */
    public Download publicDownload(WorkspaceId workspace, String route, String path) {
        String commit =
                snapshots.withCurrent(workspace, value -> value.commit().orElseThrow(MediaFileService::missing));
        return publicDownload(workspace, commit, route, path);
    }

    private ManagedAsset resolve(WorkspaceId workspace, RepositoryMediaIndex.Media entry) {
        if (entry == null) throw missing();
        var asset = originals.get().describe(workspace, new ManagedAssetReference(entry.assetId(), entry.revision()));
        if (asset.size() != entry.size() || !asset.mediaType().equals(entry.mediaType())) throw missing();
        return asset;
    }

    private static PublicArticle publicArticle(PublicContentSnapshot snapshot, String commit, String route) {
        if (!snapshot.commit().equals(Optional.ofNullable(commit))) throw missing();
        return snapshot.articles().stream()
                .filter(article -> article.route().equals(route))
                .findFirst()
                .orElseThrow(MediaFileService::missing);
    }

    private static boolean references(PublicArticle article, String path) {
        var references = MarkdownDestinations.parse(article.body());
        return java.util.stream.Stream.concat(references.links().stream(), references.images().stream())
                .anyMatch(authored -> MarkdownDestinations.path(article.repositoryPath(), authored)
                        .filter(path::equals)
                        .isPresent());
    }

    public final class Download {
        private final WorkspaceId workspace;
        private final String path;
        private final ManagedAsset asset;
        private final Runnable check;
        private final boolean publicTransfer;

        private Download(
                WorkspaceId workspace, String path, ManagedAsset asset, Runnable check, boolean publicTransfer) {
            this.workspace = workspace;
            this.path = path;
            this.asset = asset;
            this.check = check;
            this.publicTransfer = publicTransfer;
        }

        public String filename() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        public long size() {
            return asset.size();
        }

        public ManagedAsset asset() {
            return asset;
        }

        /** Leaves output open; consumers discard partial output after failure or authorization loss. */
        public void writeTo(OutputStream output) {
            check.run();
            try (var admission = admit(workspace, publicTransfer)) {
                originals.get().copyTo(workspace, asset.reference(), new OutputStream() {
                    long allowance;

                    @Override
                    public void write(int value) throws IOException {
                        write(new byte[] {(byte) value});
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        while (length > 0) {
                            if (allowance <= 0) {
                                check.run();
                                allowance = AUTHORIZATION_BYTES;
                            }
                            int count = (int) Math.min(length, allowance);
                            output.write(bytes, offset, count);
                            allowance -= count;
                            offset += count;
                            length -= count;
                        }
                    }
                });
            } catch (RuntimeException failure) {
                throw checkedFailure(check, failure);
            }
        }
    }

    private static RuntimeException checkedFailure(Runnable check, RuntimeException failure) {
        try {
            check.run();
        } catch (RuntimeException denied) {
            // Preserve the diagnostic while keeping the current authorization failure client-visible.
            if (denied != failure) denied.addSuppressed(failure);
            return denied;
        }
        return failure;
    }

    private synchronized Admission admit(WorkspaceId workspace, boolean publicTransfer) {
        // Anonymous readers cannot consume the workspace's last slot or the instance's last two slots.
        if (total >= 4
                || active.getOrDefault(workspace, 0) >= 2
                || (publicTransfer && (publicTransfers.size() >= 2 || publicTransfers.contains(workspace))))
            throw new AssetStorageException(AssetStorageException.Reason.UNAVAILABLE);
        total++;
        active.merge(workspace, 1, Integer::sum);
        if (publicTransfer) publicTransfers.add(workspace);
        return new Admission(workspace, publicTransfer);
    }

    private final class Admission implements AutoCloseable {
        private final WorkspaceId workspace;
        private final boolean publicTransfer;
        private boolean closed;

        Admission(WorkspaceId workspace, boolean publicTransfer) {
            this.workspace = workspace;
            this.publicTransfer = publicTransfer;
        }

        @Override
        public void close() {
            synchronized (MediaFileService.this) {
                if (closed) return;
                closed = true;
                total--;
                active.compute(workspace, (key, count) -> count == 1 ? null : count - 1);
                if (publicTransfer) publicTransfers.remove(workspace);
            }
        }
    }

    private static AssetStorageException missing() {
        return new AssetStorageException(AssetStorageException.Reason.NOT_FOUND);
    }
}
