package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
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

    private SandcrabProtocol() {}

    static byte[] write(Object value) {
        return JSON.writeValueAsBytes(value);
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

    private static String profile(String value) {
        require(value != null && value.matches("[a-z][a-z0-9-]{0,47}"), "profile", "must be a native profile name");
        return value;
    }

    private static String code(String value) {
        require(value != null && value.matches("[A-Z_]{1,64}"), "native error code", "must be bounded uppercase text");
        return value;
    }

    record Reference(String id, String serverId) {
        Reference {
            id = ProtocolValues.uuid(id, "native object ID");
            serverId = ProtocolValues.uuid(serverId, "native server epoch");
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
