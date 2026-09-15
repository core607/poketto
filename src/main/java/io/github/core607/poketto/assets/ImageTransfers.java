package io.github.core607.poketto.assets;

import io.github.core607.poketto.assets.internal.PublicImageDownloader;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Account-scoped image transfer grants; immutable originals and idempotency remain owned by AssetService. */
public final class ImageTransfers {
    public static final String UPLOAD_PATH = "/api/image-transfers/";
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final long COLLECTION_BYTES = 32L * 1024 * 1024;
    private final AuthService auth;
    private final AssetService assets;
    private final ImageMemoryAdmission memory;
    private final PublicImageDownloader downloader;
    private final Clock clock;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new HashMap<>();
    private final Set<UUID> collecting = new HashSet<>();

    public ImageTransfers(
            AuthService auth,
            AssetService assets,
            ImageMemoryAdmission memory,
            PublicImageDownloader downloader,
            Clock clock,
            String baseUrl) {
        this.auth = auth;
        this.assets = assets;
        this.memory = memory;
        this.downloader = downloader;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public Receipt importUrl(AuthPrincipal actor, WorkspaceId workspace, String operationKey, String url) {
        validateKey(operationKey);
        auth.authorize(actor, workspace, Capability.WRITE_PRIVATE);
        ImageRequestScope reservation = memory.acquire(ImageMemoryAdmission.MCP_BYTES)
                .orElseThrow(() -> new ImageTransferException(ImageTransferException.Reason.IMAGE_MEMORY_BUSY));
        try (var producer = reservation.producer()) {
            byte[] bytes = downloader.download(url);
            return Receipt.of(assets.upload(actor, workspace, operationKey, new ByteArrayInputStream(bytes)));
        } catch (IOException unavailable) {
            throw new ImageTransferException(ImageTransferException.Reason.SOURCE_UNAVAILABLE, unavailable);
        } finally {
            reservation.responseComplete();
        }
    }

    public Upload prepare(AuthPrincipal actor, WorkspaceId workspace, String operationKey) {
        validateKey(operationKey);
        auth.authorize(actor, workspace, Capability.WRITE_PRIVATE);
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("image transfer public URL is not configured");
        }
        synchronized (grants) {
            grants.values().removeIf(grant -> expired(grant) && !grant.busy.get());
            for (var entry : grants.entrySet()) {
                Grant grant = entry.getValue();
                if (!expired(grant)
                        && grant.actor.subjectId().equals(actor.subjectId())
                        && grant.workspace.equals(workspace)
                        && grant.operationKey.equals(operationKey)) {
                    return descriptor(entry.getKey(), grant);
                }
            }
            var owned = grants.values().stream()
                    .filter(grant -> grant.actor.accountId().equals(actor.accountId()))
                    .toList();
            long unfinished =
                    owned.stream().filter(grant -> grant.receipt == null).count();
            if (grants.size() >= 512 || owned.size() >= 64 || unfinished >= 8) {
                throw new ImageTransferException(ImageTransferException.Reason.TRANSFER_BUSY);
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var grant =
                    new Grant(actor, workspace, operationKey, clock.instant().plus(LIFETIME));
            grants.put(token, grant);
            return descriptor(token, grant);
        }
    }

    public Receipt status(String token) {
        Grant grant = resolve(token);
        Receipt receipt = grant.receipt;
        if (receipt == null) {
            throw new ImageTransferException(ImageTransferException.Reason.UPLOAD_PENDING);
        }
        return receipt;
    }

    /** The caller must hold an image-memory reservation before reading the raw upload body. */
    public Receipt upload(String token, byte[] bytes) {
        Grant grant = resolve(token);
        if (!grant.busy.compareAndSet(false, true)) {
            throw new ImageTransferException(ImageTransferException.Reason.TRANSFER_BUSY);
        }
        try {
            if (bytes.length > ManagedBlobStore.MAX_UPLOAD_BYTES) {
                throw new AssetStorageException(AssetStorageException.Reason.TOO_LARGE);
            }
            resolve(token);
            var validation = memory.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES)
                    .orElseThrow(() -> new ImageTransferException(ImageTransferException.Reason.TRANSFER_BUSY));
            try (var producer = validation.producer()) {
                Receipt receipt = Receipt.of(assets.upload(
                        grant.actor, grant.workspace, grant.operationKey, new ByteArrayInputStream(bytes)));
                grant.receipt = receipt;
                return receipt;
            } finally {
                validation.responseComplete();
            }
        } finally {
            grant.busy.set(false);
        }
    }

    public ImageRequestScope reserve(String token) {
        UUID account = resolve(token).actor.accountId();
        synchronized (collecting) {
            if (collecting.size() >= 4 || !collecting.add(account)) {
                throw new ImageTransferException(ImageTransferException.Reason.TRANSFER_BUSY);
            }
        }
        try {
            // At most four bounded buffers, leaving a page share available under the default budget.
            var reservation = memory.tryAcquire(COLLECTION_BYTES)
                    .orElseThrow(() -> new ImageTransferException(ImageTransferException.Reason.TRANSFER_BUSY));
            return new ImageRequestScope(() -> {
                reservation.responseComplete();
                releaseCollection(account);
            });
        } catch (RuntimeException failure) {
            releaseCollection(account);
            throw failure;
        }
    }

    private void releaseCollection(UUID account) {
        synchronized (collecting) {
            collecting.remove(account);
        }
    }

    private Grant resolve(String token) {
        Grant grant;
        synchronized (grants) {
            grant = grants.get(token);
        }
        if (grant == null || expired(grant)) {
            throw new ImageTransferException(ImageTransferException.Reason.UPLOAD_EXPIRED);
        }
        auth.authorize(grant.actor, grant.workspace, Capability.WRITE_PRIVATE);
        return grant;
    }

    private boolean expired(Grant grant) {
        return !clock.instant().isBefore(grant.expiresAt);
    }

    private Upload descriptor(String token, Grant grant) {
        return new Upload(
                baseUrl + UPLOAD_PATH + token,
                "PUT",
                "application/octet-stream",
                ManagedBlobStore.MAX_UPLOAD_BYTES,
                grant.expiresAt);
    }

    public static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException(
                    "operationKey requires 16-128 ASCII letters, digits, hyphens or underscores");
        }
    }

    public static void validateUrl(String url) {
        PublicImageDownloader.validateUri(url);
    }

    public record Upload(String uploadUrl, String method, String contentType, int maxBytes, Instant expiresAt) {}

    public record Receipt(UUID assetId, String revision, String reference, String mediaType, long size) {
        public static Receipt of(ManagedAsset asset) {
            return new Receipt(
                    asset.reference().assetId(),
                    asset.reference().revision(),
                    asset.reference().toString(),
                    asset.mediaType(),
                    asset.size());
        }
    }

    private static final class Grant {
        private final AuthPrincipal actor;
        private final WorkspaceId workspace;
        private final String operationKey;
        private final Instant expiresAt;
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile Receipt receipt;

        private Grant(AuthPrincipal actor, WorkspaceId workspace, String operationKey, Instant expiresAt) {
            this.actor = actor;
            this.workspace = workspace;
            this.operationKey = operationKey;
            this.expiresAt = expiresAt;
        }
    }
}
