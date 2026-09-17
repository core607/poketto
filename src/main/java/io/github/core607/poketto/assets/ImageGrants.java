package io.github.core607.poketto.assets;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short-lived tokens that authorize one image representation for one actor, workspace, page and
 * snapshot commit. A token is reused while its remaining lifetime is still useful, the registry
 * is bounded, expired entries are purged on every access, and a Git source is protected for the
 * token's lifetime before the registry lock is taken. Capacity exhaustion omits the image and is
 * logged at most once per interval, after the lock is released.
 */
final class ImageGrants {
    private static final Logger log = LoggerFactory.getLogger(ImageGrants.class);
    static final Duration GRANT_LIFETIME = Duration.ofMinutes(5);
    private static final Duration MINIMUM_REUSABLE_LIFETIME = Duration.ofMinutes(1);
    private static final Duration CAPACITY_WARNING_INTERVAL = Duration.ofMinutes(1);

    private final RepositoryBlobReader blobs;
    private final Clock clock;
    private final int maxGrants;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new HashMap<>();
    private final Map<Key, String> reusable = new HashMap<>();
    private Instant capacityWarningAt;
    private long capacityOmissions;

    ImageGrants(RepositoryBlobReader blobs, Clock clock, int maxGrants) {
        if (maxGrants < 128 || maxGrants > 100_000) {
            throw new IllegalArgumentException("image grant capacity must be 128 to 100000");
        }
        this.blobs = blobs;
        this.clock = clock;
        this.maxGrants = maxGrants;
    }

    /** What one token authorizes; equal keys share a token while it stays useful. */
    record Key(
            WorkspaceId workspace,
            String commit,
            String page,
            AssetService.Target target,
            String actor,
            boolean publicScope,
            Representation representation) {}

    enum Representation {
        ORIGINAL,
        ALBUM_THUMBNAIL_V1
    }

    record Grant(Key key, Instant issued, Instant expires) {}

    private record CapacityWarning(long omissions, int capacity) {}

    /** Empty when the registry is full; the image is then omitted rather than served unauthorized. */
    Optional<String> mint(Key key, Instant snapshotExpires) {
        Instant preparedAt = clock.instant();
        Instant expires = preparedAt.plus(GRANT_LIFETIME).isBefore(snapshotExpires)
                ? preparedAt.plus(GRANT_LIFETIME)
                : snapshotExpires;
        if (!preparedAt.isBefore(expires)) {
            throw notFound();
        }
        if (key.target() instanceof AssetService.Git git) {
            // Source retention and its workspace lock must never run under the registry lock.
            try {
                blobs.protect(git.blob(), expires);
            } catch (ContentRepositoryException unavailable) {
                throw notFound();
            }
        }
        CapacityWarning warning;
        synchronized (this) {
            Instant now = clock.instant();
            purge(now);
            if (now.isBefore(preparedAt) || !now.isBefore(expires)) {
                throw notFound();
            }
            String token = reusable.get(key);
            if (token != null) {
                Instant previousExpires = grants.get(token).expires();
                // A snapshot near expiry cannot give a replacement token a longer useful lifetime.
                if (!previousExpires.isAfter(expires)
                        && (previousExpires.equals(expires)
                                || !previousExpires.isBefore(now.plus(MINIMUM_REUSABLE_LIFETIME)))) {
                    return Optional.of(token);
                }
            }
            if (grants.size() >= maxGrants) {
                warning = capacityWarning(now);
            } else {
                byte[] entropy = new byte[32];
                do {
                    random.nextBytes(entropy);
                    token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
                } while (grants.containsKey(token));
                grants.put(token, new Grant(key, now, expires));
                reusable.put(key, token);
                return Optional.of(token);
            }
        }
        if (warning != null) {
            log.warn(
                    "Image grant capacity exhausted; omitted {} image authorization(s) since the previous warning (capacity {})",
                    warning.omissions(),
                    warning.capacity());
        }
        return Optional.empty();
    }

    /** Captures only counts under the registry lock; log output must happen after leaving it. */
    private CapacityWarning capacityWarning(Instant now) {
        if (capacityOmissions < Long.MAX_VALUE) {
            capacityOmissions++;
        }
        if (capacityWarningAt == null
                || now.isBefore(capacityWarningAt)
                || !now.isBefore(capacityWarningAt.plus(CAPACITY_WARNING_INTERVAL))) {
            var warning = new CapacityWarning(capacityOmissions, maxGrants);
            capacityOmissions = 0;
            capacityWarningAt = now;
            return warning;
        }
        return null;
    }

    synchronized Grant grant(WorkspaceId workspace, String token, String actor) {
        Grant grant = grant(token, actor);
        if (!grant.key().workspace().equals(workspace)) {
            throw notFound();
        }
        return grant;
    }

    synchronized Grant grant(String token, String actor) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw notFound();
        }
        Instant now = clock.instant();
        purge(now);
        Grant grant = grants.get(token);
        if (grant == null || !grant.key().actor().equals(actor)) {
            throw notFound();
        }
        return grant;
    }

    private void purge(Instant now) {
        var iterator = grants.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Grant grant = entry.getValue();
            if (now.isBefore(grant.issued()) || !now.isBefore(grant.expires())) {
                reusable.remove(grant.key(), entry.getKey());
                iterator.remove();
            }
        }
    }

    /** An unknown, expired, foreign or otherwise unusable token is indistinguishable from a missing image. */
    static AssetStorageException notFound() {
        return new AssetStorageException(AssetStorageException.Reason.NOT_FOUND);
    }
}
