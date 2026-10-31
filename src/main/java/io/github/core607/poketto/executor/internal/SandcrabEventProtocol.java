package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.ProtocolValues.require;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded native observations; cursors identify retained commands, never permission to repeat an effect. */
final class SandcrabEventProtocol {
    static final int OUTPUT_BYTES = 1_048_576;
    static final int CALL_BYTES = 65_536;
    private static final Set<String> RUN_STATES = Set.of(
            "running", "closing", "succeeded", "failed", "cancelled", "timed_out", "output_limit", "resource_limit");
    private static final Set<String> CALL_STATES = Set.of("pending", "replied", "uncertain", "abandoned");

    private SandcrabEventProtocol() {}

    record OutputCursor(String incarnation, int stdout, int stderr) {
        OutputCursor {
            incarnation = ProtocolValues.uuid(incarnation, "output incarnation");
            ProtocolValues.inRange(stdout, 0, OUTPUT_BYTES, "stdout offset");
            ProtocolValues.inRange(stderr, 0, OUTPUT_BYTES, "stderr offset");
            require(stdout + stderr <= OUTPUT_BYTES, "output offsets", "must fit the output limit");
        }

        static OutputCursor parse(String text) {
            String[] parts = parts(text, 3);
            return new OutputCursor(parts[0], number(parts[1]), number(parts[2]));
        }

        String text() {
            return incarnation + ":" + stdout + ":" + stderr;
        }
    }

    record CallCursor(String incarnation, int sequence) {
        CallCursor {
            incarnation = ProtocolValues.uuid(incarnation, "call incarnation");
            ProtocolValues.inRange(sequence, 0, 64, "call sequence");
        }

        static CallCursor parse(String text) {
            String[] parts = parts(text, 2);
            return new CallCursor(parts[0], number(parts[1]));
        }

        String text() {
            return incarnation + ":" + sequence;
        }
    }

    private static String[] parts(String text, int size) {
        ProtocolValues.boundedText(text, 64, "event cursor");
        String[] parts = text.split(":", -1);
        require(parts.length == size, "event cursor", "must have the expected components");
        return parts;
    }

    private static int number(String text) {
        require(text.matches("0|[1-9][0-9]{0,6}"), "cursor offset", "must be canonical bounded decimal");
        return Integer.parseInt(text);
    }

    record Outcome(Integer exitCode, Boolean truncated, String code) {
        Outcome {
            if (code == null) {
                Objects.requireNonNull(exitCode, "event exit code must be present");
                Objects.requireNonNull(truncated, "event truncation flag must be present");
            } else {
                code = SandcrabProtocol.code(code);
                require(exitCode == null && truncated == null, "event failure", "must not also claim an exit result");
            }
        }

        boolean success() {
            return code == null && exitCode == 0 && !truncated;
        }
    }

    record Run(
            String id,
            String serverId,
            String profile,
            String state,
            Outcome result,
            String sessionId,
            Integer stdoutBytes,
            Integer stderrBytes) {
        Run {
            id = ProtocolValues.uuid(id, "event run ID");
            serverId = ProtocolValues.uuid(serverId, "event server epoch");
            profile = SandcrabProtocol.profile(profile);
            require(
                    RUN_STATES.contains(Objects.requireNonNull(state, "event run state must be present")),
                    "event run state",
                    "must be known");
            sessionId = SandcrabProtocol.optionalUuid(sessionId, "event session ID");
            require(state.equals("running") == (result == null), "event result", "must match its lifecycle state");
            if (state.equals("succeeded")) {
                require(result.success(), "successful event result", "must confirm an untruncated zero exit");
            }
            require((stdoutBytes == null) == (stderrBytes == null), "event output lengths", "must occur together");
            if (stdoutBytes != null) {
                ProtocolValues.inRange(stdoutBytes, 0, OUTPUT_BYTES, "event stdout bytes");
                ProtocolValues.inRange(stderrBytes, 0, OUTPUT_BYTES, "event stderr bytes");
                require(stdoutBytes + stderrBytes <= OUTPUT_BYTES, "event output", "must fit the output limit");
            }
        }

        SandcrabProtocol.Reference reference() {
            return new SandcrabProtocol.Reference(id, serverId);
        }

        boolean terminal() {
            return !state.equals("running") && !state.equals("closing");
        }
    }

    record Output(int offset, String base64) {
        Output {
            ProtocolValues.inRange(offset, 0, OUTPUT_BYTES, "output block offset");
            require(decode(base64, 4096).length > 0, "output block", "must not be empty");
        }

        byte[] bytes() {
            return decode(base64, 4096);
        }
    }

    record CallInfo(String id, String requestId, int sequence, String state, String requestSha256, String replySha256) {
        CallInfo {
            id = ProtocolValues.uuid(id, "call ID");
            requestId = ProtocolValues.uuid(requestId, "guest request ID");
            ProtocolValues.inRange(sequence, 1, 64, "request sequence");
            require(
                    CALL_STATES.contains(Objects.requireNonNull(state, "call state must be present")),
                    "call state",
                    "must be known");
            requestSha256 = ProtocolValues.hex(requestSha256, 64, "request digest");
            if (replySha256 != null) {
                replySha256 = ProtocolValues.hex(replySha256, 64, "reply digest");
            }
            boolean attempted = state.equals("replied") || state.equals("uncertain");
            require(attempted == (replySha256 != null), "call reply digest", "must match publication state");
        }
    }

    record CallRequest(
            String id,
            String requestId,
            int sequence,
            String state,
            String requestSha256,
            String replySha256,
            String base64) {
        CallRequest {
            new CallInfo(id, requestId, sequence, state, requestSha256, replySha256);
            require(state.equals("pending"), "call request", "must be pending");
            require(
                    SandcrabProtocol.sha256(decode(base64, CALL_BYTES)).equals(requestSha256),
                    "call payload",
                    "must match its digest");
        }

        byte[] bytes() {
            return decode(base64, CALL_BYTES);
        }
    }

    record CallList(Boolean closed, String error, List<CallInfo> calls) {
        CallList {
            Objects.requireNonNull(closed, "call list closure must be present");
            if (error != null) {
                error = SandcrabProtocol.code(error);
            }
            Objects.requireNonNull(calls, "call list must be present");
            ProtocolValues.inRange(calls.size(), 0, 64, "call count");
            calls = List.copyOf(calls);
            var ids = new HashSet<String>();
            var requests = new HashSet<String>();
            for (int index = 0; index < calls.size(); index++) {
                CallInfo call = calls.get(index);
                require(call.sequence() == index + 1, "call list sequence", "must be contiguous");
                require(ids.add(call.id()) && requests.add(call.requestId()), "call list identities", "must be unique");
                require(
                        !closed || !call.state().equals("pending"),
                        "closed call list",
                        "must not contain pending work");
            }
        }
    }

    record CallState(String runId, String serverId, CallList calls) {
        CallState {
            runId = ProtocolValues.uuid(runId, "call stream run ID");
            serverId = ProtocolValues.uuid(serverId, "call stream server epoch");
            Objects.requireNonNull(calls, "call stream inventory must be present");
        }

        SandcrabProtocol.Reference reference() {
            return new SandcrabProtocol.Reference(runId, serverId);
        }
    }

    record CallInventory(
            String runId, String serverId, String incarnation, Boolean closed, String error, List<CallInfo> calls) {
        CallInventory {
            runId = ProtocolValues.uuid(runId, "call inventory run ID");
            serverId = ProtocolValues.uuid(serverId, "call inventory server epoch");
            incarnation = ProtocolValues.uuid(incarnation, "call inventory incarnation");
            calls = new CallList(closed, error, calls).calls();
        }

        SandcrabProtocol.Reference reference() {
            return new SandcrabProtocol.Reference(runId, serverId);
        }
    }

    record CallResult(Run run, CallList calls) {
        CallResult {
            Objects.requireNonNull(run, "call terminal run must be present");
            Objects.requireNonNull(calls, "call terminal inventory must be present");
            require(run.terminal() && calls.closed(), "call result", "must confirm command containment");
        }
    }

    record Reply(String base64) {
        Reply {
            decode(base64, CALL_BYTES);
        }
    }

    static byte[] decode(String value, int maximum) {
        Objects.requireNonNull(value, "event payload must be present");
        require(value.length() <= (maximum + 2) / 3 * 4, "event payload", "exceeds its encoded bound");
        byte[] bytes = Base64.getDecoder().decode(value);
        require(bytes.length <= maximum, "event payload", "exceeds its decoded bound");
        require(Base64.getEncoder().encodeToString(bytes).equals(value), "event payload", "must be canonical base64");
        return bytes;
    }
}
