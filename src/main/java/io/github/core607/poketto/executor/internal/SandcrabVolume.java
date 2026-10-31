package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;

/** A durable storage identity survives daemon epochs; its creation digest binds later observations. */
record SandcrabVolume(
        String id,
        String profile,
        String state,
        @JsonProperty(required = true) String code,
        long byteLimit,
        long inodeLimit,
        @JsonProperty(required = true) String cloneFrom,
        String planDigest,
        @JsonProperty(required = true) boolean busy,
        @JsonProperty(required = true) String attachedTo) {
    private static final Set<String> STATES = Set.of("creating", "ready", "deleting", "deleted", "failed", "closing");

    SandcrabVolume {
        id = ProtocolValues.uuid(id, "volume ID");
        profile = SandcrabProtocol.profile(profile);
        require(state != null && STATES.contains(state), "volume state", "must be a native lifecycle state");
        code = code == null ? null : SandcrabProtocol.code(code);
        byteLimit = bytes(byteLimit);
        inodeLimit = inodes(inodeLimit);
        cloneFrom = SandcrabProtocol.optionalUuid(cloneFrom, "clone source ID");
        require(!id.equals(cloneFrom), "clone source ID", "must differ from its destination");
        planDigest = ProtocolValues.hex(planDigest, 64, "volume plan digest");
        attachedTo = SandcrabProtocol.optionalUuid(attachedTo, "attached session ID");
        require(attachedTo == null || busy, "volume attachment", "must retain exclusive ownership");
    }

    boolean forgettable() {
        return !busy && (state.equals("deleted") || state.equals("failed"));
    }

    private static long bytes(long value) {
        ProtocolValues.inRange(value, 1_048_576, 1L << 50, "volume byte limit");
        require(value % 1024 == 0, "volume byte limit", "must be a multiple of 1024");
        return value;
    }

    private static long inodes(long value) {
        return ProtocolValues.inRange(value, 32, 1_000_000, "volume inode limit");
    }

    record Plan(String id, String serverId, String profile, String cloneFrom, Long byteLimit, Long inodeLimit) {
        Plan {
            id = ProtocolValues.uuid(id, "volume ID");
            serverId = ProtocolValues.uuid(serverId, "volume creation epoch");
            profile = SandcrabProtocol.profile(profile);
            cloneFrom = SandcrabProtocol.optionalUuid(cloneFrom, "clone source ID");
            require(!id.equals(cloneFrom), "clone source ID", "must differ from its destination");
            if (byteLimit != null) {
                bytes(byteLimit);
            }
            if (inodeLimit != null) {
                inodes(inodeLimit);
            }
        }
    }

    record Page(
            List<SandcrabVolume> volumes,
            @JsonProperty(required = true) String nextAfter) {
        Page {
            require(volumes != null && volumes.size() <= 100, "volume inventory", "must contain at most 100 records");
            volumes = List.copyOf(volumes);
            String previous = "";
            for (SandcrabVolume volume : volumes) {
                require(volume.id().compareTo(previous) > 0, "volume inventory", "must have unique ordered identities");
                previous = volume.id();
            }
            nextAfter = SandcrabProtocol.optionalUuid(nextAfter, "volume inventory cursor");
            require(nextAfter == null || nextAfter.equals(previous), "volume cursor", "must name the last identity");
        }
    }
}
