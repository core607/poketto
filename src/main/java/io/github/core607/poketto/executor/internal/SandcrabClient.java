package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.time.Duration;

/** Native service calls execute once; the consumer retains identities and owns reconciliation. */
final class SandcrabClient {
    // One MiB of raw command output can expand sixfold in JSON control-character escapes.
    private static final int MAX_JSON = 8 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private final SandboxHttp http;

    SandcrabClient(SandboxHttp http) {
        this.http = http;
    }

    SandcrabProtocol.Info info() {
        return request("GET", "/v1/info", null, SandcrabProtocol.Info.class, 200);
    }

    SandcrabProtocol.Run start(SandcrabProtocol.RunRequest plan) {
        SandcrabProtocol.Run run = request("POST", "/v1/runs", plan, SandcrabProtocol.Run.class, 200, 202);
        identity(plan.reference(), run);
        require(plan.profile().equals(run.profile()) && run.sessionId() == null, "Run admission changed its scope");
        return run;
    }

    SandcrabProtocol.Run get(SandcrabProtocol.Reference reference) {
        SandcrabProtocol.Run run = request("GET", path(reference), null, SandcrabProtocol.Run.class, 200);
        identity(reference, run);
        return run;
    }

    /** Cancellation is requested here; only a later terminal result confirms containment. */
    SandcrabProtocol.Run cancel(SandcrabProtocol.Reference reference) {
        SandcrabProtocol.Run run = request(
                "POST", path(reference) + "/cancel", new SandcrabProtocol.Empty(), SandcrabProtocol.Run.class, 200);
        identity(reference, run);
        return run;
    }

    /** Releasing a terminal record ends replay protection; the caller must never reuse its ID. */
    void forget(SandcrabProtocol.Run run) {
        require(run.terminal(), "An active or uncontained run cannot be forgotten");
        request(
                "POST",
                path(run.reference()) + "/forget",
                new SandcrabProtocol.Empty(),
                SandcrabProtocol.Forgotten.class,
                200);
    }

    private static String path(SandcrabProtocol.Reference reference) {
        return "/v1/runs/" + reference.id();
    }

    private static void identity(SandcrabProtocol.Reference expected, SandcrabProtocol.Run actual) {
        require(expected.equals(actual.reference()), "Native run reply changed its identity or server epoch");
    }

    private <T> T request(String method, String path, Object data, Class<T> type, int... accepted) {
        byte[] body = data == null ? null : SandcrabProtocol.write(data);
        return http.exchange(method, path, "application/json", body, REQUEST_TIMEOUT, Duration.ZERO, (head, input) -> {
            require(head.length() > 0 && head.length() <= MAX_JSON, "Native JSON response exceeds its bound");
            require("application/json".equals(head.contentType()), "Native reply is not JSON");
            byte[] bytes = input.readNBytes((int) head.length());
            require(bytes.length == head.length(), "Native response was truncated");
            for (int status : accepted) {
                if (head.status() == status) {
                    return SandcrabProtocol.read(bytes, type);
                }
            }
            if (head.status() >= 400) {
                String code = SandcrabProtocol.read(bytes, SandcrabProtocol.Failure.class)
                        .error()
                        .code();
                if (head.status() < 500) {
                    throw new Rejected(head.status(), code);
                }
                throw new WorkerUnavailableException(
                        new IOException("Native sandbox returned " + head.status() + " " + code));
            }
            throw new WorkerUnavailableException(new IOException("Unexpected native sandbox status " + head.status()));
        });
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new WorkerUnavailableException(new IOException(message));
        }
    }

    /** A request refusal does not establish the outcome of any earlier operation. */
    static final class Rejected extends IllegalStateException {
        private final int status;
        private final String code;

        Rejected(int status, String code) {
            super("Native sandbox request rejected: HTTP " + status + " " + code);
            this.status = status;
            this.code = code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }
}
