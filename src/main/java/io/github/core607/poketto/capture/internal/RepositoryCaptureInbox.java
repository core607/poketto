package io.github.core607.poketto.capture.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.capture.CaptureInbox;
import io.github.core607.poketto.content.CaptureInboxPaths;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes each capture as a new file directly in {@link CaptureInboxPaths#ROOT} through the shared
 * patch service. The server chooses the path; a taken name gets a numeric suffix, and a remote that
 * moved in between is re-read and retried a few times before the conflict is reported.
 */
final class RepositoryCaptureInbox implements CaptureInbox {
    private static final int ATTEMPTS = 4;
    private static final int NAMES = 20;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC);

    private final AuthService auth;
    private final RepositoryContentReader reader;
    private final RepositoryPatchService patches;
    private final AssetService assets;
    private final CaptureLimits limits;
    private final ObjectMapper json;
    private final Clock clock;

    RepositoryCaptureInbox(
            AuthService auth,
            RepositoryContentReader reader,
            RepositoryPatchService patches,
            AssetService assets,
            CaptureLimits limits,
            ObjectMapper json,
            Clock clock) {
        this.auth = auth;
        this.reader = reader;
        this.patches = patches;
        this.assets = assets;
        this.limits = limits;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Captured capture(AuthPrincipal actor, WorkspaceId workspace, Capture capture, Optional<InputStream> image) {
        auth.authorize(actor, workspace, Capability.CAPTURE);
        if (capture.empty() && image.isEmpty()) {
            throw new IllegalArgumentException("a capture needs a link, text, a note or an image");
        }
        // One bucket per account: a second key or the browser entrance shares the holder's limit.
        limits.consume(actor.accountId());
        Optional<ManagedAsset> stored =
                image.map(bytes -> assets.uploadCaptured(actor, workspace, "capture-" + UUID.randomUUID(), bytes));
        Instant saved = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String title = title(capture);
        String markdown = markdown(capture, title, saved, stored);
        String stem = CaptureInboxPaths.ROOT + STAMP.format(saved) + "-" + slug(title);
        RepositoryConflictException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            RepositoryFile first = reader.getFile(workspace, Optional.empty(), stem + ".md");
            String path = free(workspace, first, stem);
            try {
                var written = patches.apply(
                        actor,
                        workspace,
                        new RepositoryPatch(
                                first.commit(),
                                List.of(new RepositoryTextChange(
                                        path, true, Optional.empty(), Optional.of(markdown)))));
                return new Captured(path, written.commit());
            } catch (RepositoryConflictException moved) {
                last = moved;
            }
        }
        throw last;
    }

    private String free(WorkspaceId workspace, RepositoryFile first, String stem) {
        if (first.expectedAbsence()) {
            return stem + ".md";
        }
        for (int suffix = 2; suffix <= NAMES; suffix++) {
            String candidate = stem + "-" + suffix + ".md";
            if (reader.getFile(workspace, first.commit(), candidate).expectedAbsence()) {
                return candidate;
            }
        }
        throw new RepositoryConflictException("capture names for this minute are taken");
    }

    private String markdown(Capture capture, String title, Instant saved, Optional<ManagedAsset> image) {
        StringBuilder text = new StringBuilder("---\n");
        text.append("id: ").append(UUID.randomUUID()).append('\n');
        // JSON strings are valid YAML double-quoted scalars, so no sent text can break the header.
        text.append("title: ").append(json.writeValueAsString(title)).append('\n');
        if (!capture.url().isEmpty()) {
            text.append("source: ")
                    .append(json.writeValueAsString(capture.url()))
                    .append('\n');
        }
        text.append("saved: ").append(saved).append("\n---\n");
        if (!capture.text().isEmpty()) {
            text.append('\n');
            capture.text()
                    .lines()
                    .forEach(line ->
                            text.append(line.isBlank() ? ">" : "> " + line).append('\n'));
        }
        if (!capture.note().isEmpty()) {
            text.append('\n').append(capture.note()).append('\n');
        }
        image.ifPresent(asset -> text.append("\n![图片](managed:")
                .append(asset.reference().assetId())
                .append(':')
                .append(asset.reference().revision())
                .append(")\n"));
        return text.toString();
    }

    private static String title(Capture capture) {
        if (!capture.title().isEmpty()) {
            return capture.title().replaceAll("\\s+", " ");
        }
        if (!capture.url().isEmpty()) {
            return URI.create(capture.url()).getHost();
        }
        String first = (capture.text().isEmpty() ? capture.note() : capture.text())
                .lines()
                .findFirst()
                .orElse("")
                .strip();
        return first.isEmpty()
                ? "收集"
                : first.codePoints()
                        .limit(40)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
    }

    /** Letters and digits of the title, joined by hyphens, at most 40 code points. */
    static String slug(String title) {
        String joined = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        String bounded = joined.codePoints()
                .limit(40)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        bounded = bounded.replaceAll("-+$", "");
        return bounded.isEmpty() ? "note" : bounded;
    }
}
