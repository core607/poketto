package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.paths;
import static io.github.core607.poketto.executor.internal.ProtocolValues.require;
import static io.github.core607.poketto.executor.internal.ProtocolValues.uuid;

import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.content.RepositoryPatch;
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
        return new Sync(string(exactly(arguments, Sync.class), "path"));
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

    record Sync(String path) {}

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
