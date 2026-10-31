package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.paths;
import static io.github.core607.poketto.executor.internal.ProtocolValues.require;
import static io.github.core607.poketto.executor.internal.ProtocolValues.uuid;

import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPaths;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What a {@code poketto} command sent, read once into a record instead of being probed field by
 * field where it is used. A command comes from inside the sandbox, so every argument is untrusted:
 * the field set must match exactly, each value must have the declared type, and the bounds are
 * applied on construction.
 *
 * <p>The expected field set comes from the record's own components, so a record and the frame it
 * accepts cannot drift apart. Every rejection is an {@link IllegalArgumentException}, which the
 * command dispatcher turns into the operation's invalid-request code.
 */
final class BridgeArguments {

    private BridgeArguments() {}

    static Status status(JsonNode arguments) {
        if (object(arguments, "status").isEmpty()) {
            return new Status(false);
        }
        return new Status(bool(exactly(arguments, Status.class), "remote"));
    }

    record Status(boolean remote) {}

    static PlanAccept planAccept(JsonNode arguments) {
        JsonNode fields = exactly(arguments, PlanAccept.class);
        JsonNode entries = fields.path("files");
        require(
                entries.isArray() && entries.size() <= 64,
                "accepted files",
                "must be an array of at most 64 expectations");
        var files = new ArrayList<PlanExpected>();
        for (JsonNode entry : entries) {
            JsonNode expected = exactly(entry, PlanExpected.class);
            files.add(new PlanExpected(string(expected, "path"), string(expected, "sha256")));
        }
        return new PlanAccept(string(fields, "planId"), files);
    }

    static PlanPage planPage(JsonNode arguments) {
        JsonNode fields = exactly(arguments, PlanPage.class);
        return new PlanPage(string(fields, "planId"), integer(fields, "offset"), integer(fields, "limit"));
    }

    static PlanIdentity planIdentity(JsonNode arguments) {
        return new PlanIdentity(string(exactly(arguments, PlanIdentity.class), "planId"));
    }

    static PlanFile planFile(JsonNode arguments) {
        JsonNode fields = exactly(arguments, PlanFile.class);
        return new PlanFile(
                string(fields, "planId"), string(fields, "path"), string(fields, "version"), string(fields, "output"));
    }

    record PlanExpected(String path, String sha256) {
        PlanExpected {
            path = RepositoryPaths.validate(path);
            sha256 = NativeRepositoryPlan.identity(sha256);
        }
    }

    record PlanAccept(String planId, List<PlanExpected> files) {
        PlanAccept {
            planId = uuid(planId, "plan identity");
            files = List.copyOf(files);
            require(
                    files.size() <= 64
                            && files.stream().map(PlanExpected::path).distinct().count() == files.size(),
                    "accepted files",
                    "must contain at most 64 distinct paths");
        }
    }

    record PlanPage(String planId, int offset, int limit) {
        PlanPage {
            planId = uuid(planId, "plan identity");
            ProtocolValues.inRange(offset, 0, WorkspaceSyncInputs.MAX_PATHS, "plan page offset");
            ProtocolValues.inRange(limit, 1, 64, "plan page limit");
        }
    }

    record PlanIdentity(String planId) {
        PlanIdentity {
            planId = uuid(planId, "plan identity");
        }
    }

    record PlanFile(String planId, String path, String version, String output) {
        PlanFile {
            planId = uuid(planId, "plan identity");
            path = RepositoryPaths.validate(path);
            output = RepositoryPaths.validate(output);
            require(
                    Set.of("original", "candidate").contains(version),
                    "plan file version",
                    "must be original or candidate");
        }
    }

    static Receipt receipt(JsonNode arguments) {
        JsonNode fields = exactly(arguments, Receipt.class);
        return new Receipt(
                string(fields, "kind"),
                integer(fields, "offset"),
                integer(fields, "limit"),
                nullableString(fields, "sha256"));
    }

    record Receipt(String kind, int offset, int limit, String sha256) {
        Receipt {
            require(Set.of("save", "import").contains(kind), "receipt kind", "must be save or import");
            ProtocolValues.inRange(offset, 0, 65536, "receipt offset");
            ProtocolValues.inRange(limit, 1, 16384, "receipt page limit");
            if (sha256 != null) {
                sha256 = ProtocolValues.hex(sha256, 64, "receipt digest");
            }
            require(offset == 0 || sha256 != null, "receipt digest", "must identify every continuation");
        }
    }

    static ArtifactCreate artifactCreate(JsonNode arguments) {
        var fields = exactly(arguments, ArtifactCreate.class);
        return new ArtifactCreate(string(fields, "path"), string(fields, "mediaType"));
    }

    static ArtifactRemove artifactRemove(JsonNode arguments) {
        return new ArtifactRemove(string(exactly(arguments, ArtifactRemove.class), "artifactId"));
    }

    /**
     * A recovery takes nothing, or the single flag that releases a confirmed move. The flag is
     * accepted only when set: an explicit {@code false} is a request the client never sends and is
     * refused rather than silently read as its own default.
     */
    static boolean recoverSkipsLocal(JsonNode arguments) {
        JsonNode fields = object(arguments, "recover");
        require(
                Set.copyOf(fields.propertyNames()).stream().allMatch("skipLocal"::equals),
                "recover",
                "takes only skipLocal");
        if (fields.isEmpty()) {
            return false;
        }
        JsonNode flag = fields.path("skipLocal");
        require(flag.isBoolean() && flag.booleanValue(), "skipLocal", "must be true when present");
        return true;
    }

    static Move move(JsonNode arguments) {
        var fields = exactly(arguments, Move.class);
        return new Move(string(fields, "source"), string(fields, "destination"));
    }

    static Sync sync(JsonNode arguments) {
        exactly(arguments, Sync.class);
        return new Sync();
    }

    static Edit edit(JsonNode arguments) {
        var fields = exactly(arguments, Edit.class);
        return new Edit(string(fields, "path"), string(fields, "oldText"), string(fields, "newText"));
    }

    static Create create(JsonNode arguments) {
        var fields = exactly(arguments, Create.class);
        return new Create(string(fields, "path"), string(fields, "text"));
    }

    static Save save(JsonNode arguments) {
        var fields = exactly(arguments, Save.class);
        return new Save(selected(fields, "writes"), selected(fields, "deletes"));
    }

    static MediaFetch mediaFetch(JsonNode arguments) {
        var fields = exactly(arguments, MediaFetch.class);
        return new MediaFetch(
                string(fields, "path"), nullableString(fields, "commit"), nullableString(fields, "output"));
    }

    static MediaImport mediaImport(JsonNode arguments) {
        var fields = exactly(arguments, MediaImport.class);
        return new MediaImport(
                string(fields, "file"),
                string(fields, "path"),
                string(fields, "mediaType"),
                string(fields, "key"),
                bool(fields, "replace"));
    }

    static MediaLink mediaLink(JsonNode arguments) {
        JsonNode fields = exactly(arguments, MediaLink.class);
        return new MediaLink(
                string(fields, "path"), string(fields, "assetId"), string(fields, "revision"), bool(fields, "replace"));
    }

    record MediaLink(String path, String assetId, String revision, boolean replace) {
        MediaLink {
            assetId = uuid(assetId, "assetId");
            revision = ProtocolValues.hex(revision, 64, "revision");
        }

        ManagedAssetReference reference() {
            return new ManagedAssetReference(UUID.fromString(assetId), revision);
        }
    }

    static Export export(JsonNode arguments) {
        var fields = exactly(arguments, Export.class);
        var selections = new ArrayList<String>();
        JsonNode values = fields.path("paths");
        require(values.isArray(), "paths", "must be an array");
        for (JsonNode value : values) {
            require(value.isString(), "paths entry", "must be a string");
            selections.add(value.stringValue());
        }
        return new Export(List.copyOf(selections), string(fields, "output"), bool(fields, "publicOnly"));
    }

    record ArtifactCreate(String path, String mediaType) {}

    record ArtifactRemove(String artifactId) {
        ArtifactRemove {
            artifactId = uuid(artifactId, "artifactId");
        }
    }

    record Move(String source, String destination) {}

    record Sync() {}

    record Edit(String path, String oldText, String newText) {
        Edit {
            require(!oldText.isEmpty(), "oldText", "must not be empty");
        }
    }

    record Create(String path, String text) {}

    record Save(List<String> writes, List<String> deletes) {
        Save {
            writes = paths(writes, RepositoryPatch.MAX_CHANGES, "writes");
            deletes = paths(deletes, RepositoryPatch.MAX_CHANGES, "deletes");
            int selected = writes.size() + deletes.size();
            require(
                    selected >= 1 && selected <= RepositoryPatch.MAX_CHANGES,
                    "selection",
                    "must name 1 to " + RepositoryPatch.MAX_CHANGES + " distinct files");
        }
    }

    /** A null commit means the session's current index, and a null output means the source path. */
    record MediaFetch(String path, String commit, String output) {}

    record MediaImport(String file, String path, String mediaType, String key, boolean replace) {
        MediaImport {
            require(key.matches("[A-Za-z0-9_-]{16,128}"), "key", "must be 16 to 128 letters, digits, _ or -");
            ManagedAsset.validateMediaType(mediaType);
        }
    }

    record Export(List<String> paths, String output, boolean publicOnly) {
        Export {
            paths = List.copyOf(paths);
            SessionExportSelection.validate(output);
        }
    }

    private static JsonNode object(JsonNode arguments, String operation) {
        require(arguments != null && arguments.isObject(), operation, "arguments must be a JSON object");
        return arguments;
    }

    /**
     * Accepts the frame only when its field names are exactly the record's components. Deriving
     * the set from the record is what keeps a renamed component from silently accepting the old
     * name as an extra field.
     */
    private static JsonNode exactly(JsonNode arguments, Class<?> shape) {
        String operation = shape.getSimpleName();
        JsonNode fields = object(arguments, operation);
        Set<String> expected = new LinkedHashSet<>(Arrays.stream(shape.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
        require(Set.copyOf(fields.propertyNames()).equals(expected), operation, "takes exactly " + expected);
        return fields;
    }

    /**
     * Type only. A path or name bound belongs to whatever consumes the value, and applying one
     * here would reject frames the worker is the one entitled to refuse.
     */
    private static String string(JsonNode fields, String field) {
        JsonNode value = fields.path(field);
        require(value.isString(), field, "must be a string");
        return value.stringValue();
    }

    private static String nullableString(JsonNode fields, String field) {
        JsonNode value = fields.path(field);
        if (value.isNull()) {
            return null;
        }
        return string(fields, field);
    }

    private static boolean bool(JsonNode fields, String field) {
        JsonNode value = fields.path(field);
        require(value.isBoolean(), field, "must be a boolean");
        return value.booleanValue();
    }

    private static int integer(JsonNode fields, String field) {
        JsonNode value = fields.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt(), field, "must be an integer");
        return value.intValue();
    }

    private static List<String> selected(JsonNode fields, String field) {
        JsonNode values = fields.path(field);
        require(values.isArray(), field, "must be an array");
        var selection = new ArrayList<String>();
        for (JsonNode value : values) {
            require(value.isString(), field + " entry", "must be a string");
            selection.add(value.stringValue());
        }
        return selection;
    }
}
