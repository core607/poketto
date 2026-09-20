package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryInitialization;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositoryTextChange;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Initializes a connected repository from the content template this build ships on its classpath,
 * so the service adds the same files the documentation describes. Presence is checked at one
 * commit and the patch names that commit as its base: a push that lands in between surfaces as a
 * conflict, never as an overwritten file.
 */
final class ContentRepositoryInitializer implements RepositoryInitialization {
    private static final String TEMPLATE = "/content-template/";

    private final AuthService auth;
    private final RepositoryContentReader reader;
    private final RepositoryPatchService patches;
    private final Map<String, String> template;

    ContentRepositoryInitializer(AuthService auth, RepositoryContentReader reader, RepositoryPatchService patches) {
        this.auth = auth;
        this.reader = reader;
        this.patches = patches;
        this.template = Collections.unmodifiableMap(loadTemplate());
    }

    private static Map<String, String> loadTemplate() {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : FILES) {
            try (InputStream stream = ContentRepositoryInitializer.class.getResourceAsStream(TEMPLATE + path)) {
                if (stream == null) {
                    throw new IllegalStateException("content template is missing " + path);
                }
                files.put(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("content template cannot be read", exception);
            }
        }
        return files;
    }

    /** The shipped template, by path. */
    Map<String, String> template() {
        return template;
    }

    @Override
    public Status status(AuthPrincipal actor, WorkspaceId workspace) {
        auth.authorize(actor, workspace, Capability.MANAGE_KEYS);
        Inspection current = inspect(workspace);
        return new Status(current.commit().isEmpty(), current.missing());
    }

    @Override
    public Outcome apply(AuthPrincipal actor, WorkspaceId workspace) {
        return initialize(actor, workspace, patch -> patches.apply(actor, workspace, patch));
    }

    @Override
    public Outcome apply(AuthPrincipal actor, WorkspaceId workspace, Runnable beforeWrite) {
        Objects.requireNonNull(beforeWrite, "Initialization ownership check is required");
        // Initialization retries inspect missing template files; this checkpoint guards ownership,
        // rather than claiming to retain a raw Git write for exact-commit recovery.
        return initialize(
                actor, workspace, patch -> patches.apply(actor, workspace, patch, attempt -> beforeWrite.run()));
    }

    private Outcome initialize(
            AuthPrincipal actor, WorkspaceId workspace, Function<RepositoryPatch, RepositoryPatchResult> writer) {
        auth.authorize(actor, workspace, Capability.MANAGE_KEYS);
        Inspection current = inspect(workspace);
        if (current.missing().isEmpty()) {
            return new Outcome(current.commit().orElseThrow(), List.of());
        }
        List<RepositoryTextChange> changes = current.missing().stream()
                .map(path -> new RepositoryTextChange(path, true, Optional.empty(), Optional.of(template.get(path))))
                .toList();
        RepositoryPatchResult result = writer.apply(new RepositoryPatch(current.commit(), changes));
        return new Outcome(result.commit(), current.missing());
    }

    // The first read selects current main; the other paths are read at that same commit.
    private Inspection inspect(WorkspaceId workspace) {
        RepositoryFile first = reader.getFile(workspace, Optional.empty(), FILES.getFirst());
        List<String> missing = new ArrayList<>();
        for (String path : FILES) {
            RepositoryFile file =
                    path.equals(FILES.getFirst()) ? first : reader.getFile(workspace, first.commit(), path);
            if (file.expectedAbsence()) {
                missing.add(path);
            }
        }
        return new Inspection(first.commit(), List.copyOf(missing));
    }

    private record Inspection(Optional<String> commit, List<String> missing) {}
}
