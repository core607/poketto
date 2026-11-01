package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Native service records; product identity and authorization do not enter this protocol. */
final class SandcrabProtocol {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private static final Set<String> RUN_STATES = Set.of(
            "running", "closing", "succeeded", "failed", "cancelled", "timed_out", "output_limit", "resource_limit");
    private static final Set<String> SESSION_STATES =
            Set.of("starting", "ready", "busy", "stopping", "closed", "expired", "failed", "closing");
    private static final Set<String> UPLOAD_STATES =
            Set.of("waiting", "receiving", "publishing", "published", "succeeded", "failed", "unknown", "closing");

    private SandcrabProtocol() {}

    static byte[] write(Object value) {
        return JSON.writeValueAsBytes(value);
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for native file transfers", unavailable);
        }
    }

    static String optionalUuid(String value, String field) {
        return value == null ? null : ProtocolValues.uuid(value, field);
    }

    private static String relativePath(String value) {
        ProtocolValues.boundedText(value, 4096, "upload path");
        require(
                !value.contains("\\") && value.chars().noneMatch(Character::isISOControl),
                "upload path",
                "must be relative and contain no controls");
        String[] components = value.split("/", -1);
        ProtocolValues.inRange(components.length, 1, 64, "upload path component count");
        for (String component : components) {
            ProtocolValues.boundedText(component, 255, "upload path component");
            require(!component.equals(".") && !component.equals(".."), "upload path", "must not traverse parents");
        }
        return value;
    }

    static <T> T read(byte[] body, Class<T> type) {
        try {
            T value = JSON.readValue(body, type);
            require(value != null, "native response", "must be present");
            return value;
        } catch (JacksonException | IllegalArgumentException malformed) {
            // Jackson diagnostics can quote sandbox output, including private content.
            // A malformed response is uncertain; neither its source nor its cause is retained.
            throw new WorkerUnavailableException();
        }
    }

    static String profile(String value) {
        require(value != null && value.matches("[a-z][a-z0-9-]{0,47}"), "profile", "must be a native profile name");
        return value;
    }

    static String code(String value) {
        require(value != null && value.matches("[A-Z_]{1,64}"), "native error code", "must be bounded uppercase text");
        return value;
    }

    record Reference(String id, String serverId) {
        Reference {
            id = ProtocolValues.uuid(id, "native object ID");
            serverId = ProtocolValues.uuid(serverId, "native server epoch");
        }
    }

    record IdentityPage(
            String serverId,
            List<String> ids,
            @JsonProperty(required = true) String nextAfter) {
        IdentityPage {
            serverId = ProtocolValues.uuid(serverId, "inventory epoch");
            require(ids != null && ids.size() <= 64, "inventory", "must contain at most 64 identities");
            ids = List.copyOf(ids);
            String previous = "";
            for (String id : ids) {
                ProtocolValues.uuid(id, "inventory identity");
                require(id.compareTo(previous) > 0, "inventory", "must contain unique identities in UUID order");
                previous = id;
            }
            nextAfter = optionalUuid(nextAfter, "inventory cursor");
            require(nextAfter == null || nextAfter.equals(previous), "inventory cursor", "must name the last identity");
        }
    }

    record UploadPage(
            List<Upload> uploads,
            @JsonProperty(required = true) String nextAfter) {
        UploadPage {
            require(uploads != null && uploads.size() <= 10, "upload inventory", "must contain at most ten records");
            uploads = List.copyOf(uploads);
            String previous = "";
            for (Upload upload : uploads) {
                require(upload.id().compareTo(previous) > 0, "upload inventory", "must have unique ordered identities");
                previous = upload.id();
            }
            nextAfter = optionalUuid(nextAfter, "upload inventory cursor");
            require(nextAfter == null || nextAfter.equals(previous), "upload cursor", "must name the last identity");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Info(int apiVersion, String serverId, Set<String> capabilities, Map<String, Profile> profiles) {
        Info {
            require(apiVersion == 1, "native API version", "must be 1");
            serverId = ProtocolValues.uuid(serverId, "native server epoch");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "native capabilities must be present"));
            require(capabilities.contains("runs"), "native capabilities", "must support runs");
            profiles = Map.copyOf(Objects.requireNonNull(profiles, "native profiles must be present"));
            ProtocolValues.inRange(profiles.size(), 1, 32, "native profile count");
            profiles.keySet().forEach(SandcrabProtocol::profile);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(
            long timeoutMillis,
            int outputLimitBytes,
            String network,
            long sessionTtlMillis,
            long transferBytes,
            boolean hostCalls) {
        Profile {
            ProtocolValues.inRange(timeoutMillis, 1, 300_000, "profile timeout");
            ProtocolValues.inRange(outputLimitBytes, 1, 1_048_576, "profile output bound");
            require("none".equals(network), "profile network", "must be none");
            ProtocolValues.inRange(sessionTtlMillis, 100, 86_400_000, "profile session lifetime");
            ProtocolValues.inRange(transferBytes, 1024, 1_073_741_824, "profile transfer bound");
        }
    }

    record RunRequest(
            String id,
            String serverId,
            String profile,
            List<String> command,
            long timeoutMillis,
            int outputLimitBytes) {
        RunRequest {
            id = ProtocolValues.uuid(id, "run ID");
            serverId = ProtocolValues.uuid(serverId, "run epoch");
            profile = SandcrabProtocol.profile(profile);
            command = List.copyOf(Objects.requireNonNull(command, "command arguments must be present"));
            ProtocolValues.inRange(command.size(), 1, 128, "argument count");
            require(!command.getFirst().isEmpty(), "command executable", "must not be empty");
            int bytes = 0;
            for (String argument : command) {
                ProtocolValues.withoutNul(argument, "command argument");
                bytes += ProtocolValues.inRange(
                        argument.getBytes(StandardCharsets.UTF_8).length, 0, 4096, "argument bytes");
            }
            ProtocolValues.inRange(bytes, 1, 16384, "command bytes");
            ProtocolValues.inRange(timeoutMillis, 1, 300_000, "run timeout");
            ProtocolValues.inRange(outputLimitBytes, 1, 1_048_576, "run output bound");
        }

        Reference reference() {
            return new Reference(id, serverId);
        }
    }

    record SessionPlan(String id, String serverId, String profile, long ttlMillis, String volumeId) {
        SessionPlan {
            id = ProtocolValues.uuid(id, "session ID");
            serverId = ProtocolValues.uuid(serverId, "session epoch");
            profile = SandcrabProtocol.profile(profile);
            ProtocolValues.inRange(ttlMillis, 100, 86_400_000, "session lifetime");
            volumeId = optionalUuid(volumeId, "session volume ID");
        }

        Reference reference() {
            return new Reference(id, serverId);
        }
    }

    record Renewal(long ttlMillis) {
        Renewal {
            ProtocolValues.inRange(ttlMillis, 100, 86_400_000, "session renewal lifetime");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Session(
            String id,
            String serverId,
            String profile,
            String state,
            long expiresAtMillis,
            @JsonProperty(required = true) String activeRunId,
            @JsonProperty(required = true) String volumeId,
            @JsonProperty(required = true) String code,
            @JsonProperty(required = true) String parentSessionId) {
        Session {
            id = ProtocolValues.uuid(id, "session ID");
            serverId = ProtocolValues.uuid(serverId, "session epoch");
            profile = SandcrabProtocol.profile(profile);
            require(
                    state != null && SESSION_STATES.contains(state),
                    "session state",
                    "must be a native lifecycle state");
            ProtocolValues.inRange(expiresAtMillis, 1, ProtocolValues.MAX_SAFE_INTEGER, "session expiry estimate");
            activeRunId = optionalUuid(activeRunId, "active command ID");
            volumeId = optionalUuid(volumeId, "session volume ID");
            code = code == null ? null : SandcrabProtocol.code(code);
            parentSessionId = optionalUuid(parentSessionId, "parent session ID");
        }

        Reference reference() {
            return new Reference(id, serverId);
        }

        boolean terminal() {
            return state.equals("closed") || state.equals("expired") || state.equals("failed");
        }
    }

    record CommandPlan(String id, String serverId, String command, long timeoutMillis, int outputLimitBytes) {
        CommandPlan {
            id = ProtocolValues.uuid(id, "command ID");
            serverId = ProtocolValues.uuid(serverId, "command epoch");
            ProtocolValues.withoutNul(ProtocolValues.boundedText(command, 16384, "command source"), "command source");
            ProtocolValues.inRange(timeoutMillis, 1, 300_000, "command timeout");
            ProtocolValues.inRange(outputLimitBytes, 1, 1_048_576, "command output bound");
        }

        Reference reference() {
            return new Reference(id, serverId);
        }
    }

    record UploadPlan(
            String id,
            String serverId,
            String sessionId,
            String path,
            String format,
            long bytes,
            String sha256,
            boolean replace,
            boolean freeze,
            int mode) {
        UploadPlan {
            id = ProtocolValues.uuid(id, "upload ID");
            serverId = ProtocolValues.uuid(serverId, "upload epoch");
            sessionId = ProtocolValues.uuid(sessionId, "upload session ID");
            path = relativePath(path);
            require("raw".equals(format) || "tar".equals(format), "upload format", "must be raw or tar");
            ProtocolValues.inRange(bytes, 0, 1_073_741_824, "upload bytes");
            sha256 = ProtocolValues.hex(sha256, 64, "upload digest");
            ProtocolValues.inRange(mode, 0, 0777, "upload mode");
        }

        Reference reference() {
            return new Reference(id, serverId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Upload(
            String id,
            String serverId,
            String sessionId,
            String profile,
            @JsonProperty(required = true) String volumeId,
            String path,
            String format,
            long bytes,
            String sha256,
            String planDigest,
            String state,
            @JsonProperty(required = true) String code,
            String publication) {
        Upload {
            id = ProtocolValues.uuid(id, "upload ID");
            serverId = ProtocolValues.uuid(serverId, "upload epoch");
            sessionId = ProtocolValues.uuid(sessionId, "upload session ID");
            profile = SandcrabProtocol.profile(profile);
            volumeId = optionalUuid(volumeId, "upload volume ID");
            path = relativePath(path);
            require("raw".equals(format) || "tar".equals(format), "upload format", "must be raw or tar");
            ProtocolValues.inRange(bytes, 0, 1_073_741_824, "upload bytes");
            sha256 = ProtocolValues.hex(sha256, 64, "upload digest");
            planDigest = ProtocolValues.hex(planDigest, 64, "upload plan digest");
            require(state != null && UPLOAD_STATES.contains(state), "upload state", "must be a native lifecycle state");
            code = code == null ? null : SandcrabProtocol.code(code);
            require(
                    "none".equals(publication) || "unknown".equals(publication) || "committed".equals(publication),
                    "upload publication",
                    "must describe publication certainty");
            require(
                    !state.equals("succeeded") || publication.equals("committed"),
                    "successful upload",
                    "must confirm publication");
        }

        Reference reference() {
            return new Reference(id, serverId);
        }

        boolean succeeded() {
            return state.equals("succeeded");
        }

        boolean forgettable() {
            return state.equals("waiting")
                    || state.equals("succeeded")
                    || state.equals("failed")
                    || state.equals("unknown");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Run(String id, String serverId, String profile, String state, Result result, String sessionId) {
        Run {
            id = ProtocolValues.uuid(id, "run ID");
            serverId = ProtocolValues.uuid(serverId, "run epoch");
            profile = SandcrabProtocol.profile(profile);
            require(state != null && RUN_STATES.contains(state), "run state", "must be a native lifecycle state");
            if (sessionId != null) {
                sessionId = ProtocolValues.uuid(sessionId, "command session ID");
            }
            require("running".equals(state) == (result == null), "run result", "must match its lifecycle state");
            if ("succeeded".equals(state)) {
                require(result.success(), "successful run", "must have complete output and exit zero");
            }
        }

        Reference reference() {
            return new Reference(id, serverId);
        }

        boolean terminal() {
            return !state.equals("running") && !state.equals("closing");
        }
    }

    record Result(Integer exitCode, String stdout, String stderr, Boolean truncated, String code) {
        Result {
            if (code == null) {
                Objects.requireNonNull(exitCode, "run exit code must be present");
                Objects.requireNonNull(truncated, "run truncation flag must be present");
                output(stdout, "stdout");
                output(stderr, "stderr");
            } else {
                code = SandcrabProtocol.code(code);
                require(
                        exitCode == null && stdout == null && stderr == null && truncated == null,
                        "failed run result",
                        "must not also claim output");
            }
        }

        private static void output(String value, String field) {
            Objects.requireNonNull(value, field + " must be present");
            ProtocolValues.inRange(value.getBytes(StandardCharsets.UTF_8).length, 0, 3 * 1_048_576, field + " bytes");
        }

        boolean success() {
            return code == null && exitCode == 0 && !truncated;
        }
    }

    record Failure(Error error) {
        Failure {
            Objects.requireNonNull(error, "native error must be present");
        }
    }

    record Error(String code) {
        Error {
            code = SandcrabProtocol.code(code);
        }
    }

    record Forgotten(boolean forgotten) {
        Forgotten {
            require(forgotten, "native forget receipt", "must confirm release");
        }
    }

    record Empty() {}
}
