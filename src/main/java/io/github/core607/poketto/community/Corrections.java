package io.github.core607.poketto.community;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts.Profile;
import io.github.core607.poketto.community.Community.Page;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Body corrections that signed-in readers propose for a public article. Nothing reaches Git until
 * a member holding {@code PUBLISH} accepts a proposal, and only while the article's body still
 * equals the text the reader started from.
 */
public interface Corrections {
    /** At most 1 MiB of body text, in UTF-8. */
    int MAX_BODY_BYTES = 1024 * 1024;

    UUID propose(AuthPrincipal actor, String space, Proposal proposal);

    /** The caller's latest proposal for the route, whatever its status. */
    Optional<Mine> mine(AuthPrincipal actor, String space, String route);

    void withdraw(AuthPrincipal actor, UUID correctionId);

    /** Display names credited for accepted corrections of a public route, earliest first. */
    List<String> credits(String space, String route);

    /** Open proposals of one space, for members holding {@code PUBLISH}. */
    Page<Review> open(AuthPrincipal actor, WorkspaceId workspace, long before);

    Resolution accept(AuthPrincipal actor, WorkspaceId workspace, UUID correctionId);

    void decline(AuthPrincipal actor, WorkspaceId workspace, UUID correctionId);

    record Proposal(String route, String baseDigest, String body, String reason) {
        public Proposal {
            if (route == null || route.length() > 256 || !route.startsWith("/")) {
                throw new IllegalArgumentException("correction route is invalid");
            }
            if (baseDigest == null || !baseDigest.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("correction base digest is invalid");
            }
            if (body == null
                    || body.isBlank()
                    || body.indexOf('\0') >= 0
                    || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("correction body must be non-blank text of at most 1 MiB");
            }
            reason = reason == null ? "" : reason.strip();
            if (reason.codePointCount(0, reason.length()) > 500 || reason.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("correction reason exceeds 500 characters");
            }
        }
    }

    record Mine(UUID id, String status, Instant createdAt) {}

    /** {@code space} is the public slug; {@code stale} means the served body no longer matches the base. */
    record Review(
            UUID id,
            long position,
            String space,
            String route,
            String title,
            Profile author,
            String reason,
            String proposedBody,
            String currentBody,
            boolean stale,
            Instant createdAt) {}

    enum Resolution {
        ACCEPTED,
        STALE
    }
}
