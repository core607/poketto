package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/** Native service calls execute once; the consumer retains identities and owns reconciliation. */
final class SandcrabClient {
    // One MiB of raw command output can expand sixfold in JSON control-character escapes.
    private static final int MAX_JSON = 8 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(90);
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

    SandcrabProtocol.Session open(SandcrabProtocol.SessionPlan plan) {
        SandcrabProtocol.Session session =
                request("POST", "/v1/sessions", plan, SandcrabProtocol.Session.class, 200, 202);
        require(plan.reference().equals(session.reference()), "Session admission changed its identity or epoch");
        require(
                plan.profile().equals(session.profile())
                        && Objects.equals(plan.volumeId(), session.volumeId())
                        && session.parentSessionId() == null,
                "Session admission changed its workspace scope");
        return session;
    }

    SandcrabProtocol.Session session(SandcrabProtocol.Reference reference) {
        SandcrabProtocol.Session session =
                request("GET", sessionPath(reference), null, SandcrabProtocol.Session.class, 200);
        require(reference.equals(session.reference()), "Native session reply changed its identity or epoch");
        return session;
    }

    SandcrabProtocol.Run command(SandcrabProtocol.Session session, SandcrabProtocol.CommandPlan plan) {
        ProtocolValues.require(session.serverId().equals(plan.serverId()), "command epoch", "must match its session");
        SandcrabProtocol.Run run = request(
                "POST", sessionPath(session.reference()) + "/commands", plan, SandcrabProtocol.Run.class, 200, 202);
        identity(plan.reference(), run);
        require(
                session.id().equals(run.sessionId()) && session.profile().equals(run.profile()),
                "Native command reply changed its session scope");
        return run;
    }

    /** Closing is asynchronous; stopping or closing does not establish containment. */
    SandcrabProtocol.Session close(SandcrabProtocol.Reference reference) {
        SandcrabProtocol.Session session = request(
                "POST",
                sessionPath(reference) + "/close",
                new SandcrabProtocol.Empty(),
                SandcrabProtocol.Session.class,
                200);
        require(reference.equals(session.reference()), "Native close reply changed its identity or epoch");
        return session;
    }

    void forget(SandcrabProtocol.Session session) {
        require(session.terminal(), "An active or uncontained session cannot be forgotten");
        request(
                "POST",
                sessionPath(session.reference()) + "/forget",
                new SandcrabProtocol.Empty(),
                SandcrabProtocol.Forgotten.class,
                200);
    }

    SandcrabProtocol.Upload register(SandcrabProtocol.Session session, SandcrabProtocol.UploadPlan plan) {
        ProtocolValues.require(
                session.serverId().equals(plan.serverId()) && session.id().equals(plan.sessionId()),
                "upload session",
                "must match its retained session identity");
        SandcrabProtocol.Upload upload = request("POST", "/v1/uploads", plan, SandcrabProtocol.Upload.class, 200, 201);
        manifest(plan, upload);
        require(
                session.profile().equals(upload.profile()) && Objects.equals(session.volumeId(), upload.volumeId()),
                "Native upload admission changed its storage scope");
        return upload;
    }

    /** Sends one bounded in-memory body; retain the plan before dispatch and query it after response loss. */
    SandcrabProtocol.Upload send(SandcrabProtocol.UploadPlan plan, byte[] content) {
        ProtocolValues.require(
                content.length <= SandboxHttp.MAX_REQUEST_BYTES, "upload body", "exceeds the in-memory client limit");
        byte[] bytes = content.clone();
        ProtocolValues.require(
                bytes.length == plan.bytes() && SandcrabProtocol.sha256(bytes).equals(plan.sha256()),
                "upload body",
                "must match its registered size and digest");
        SandcrabProtocol.Upload upload = exchange(
                "PUT",
                uploadPath(plan.reference()) + "/content",
                "application/octet-stream",
                bytes,
                UPLOAD_TIMEOUT,
                SandcrabProtocol.Upload.class,
                200);
        manifest(plan, upload);
        return upload;
    }

    SandcrabProtocol.Upload upload(SandcrabProtocol.Reference reference) {
        SandcrabProtocol.Upload upload =
                request("GET", uploadPath(reference), null, SandcrabProtocol.Upload.class, 200);
        require(reference.equals(upload.reference()), "Native upload reply changed its identity or epoch");
        return upload;
    }

    SandcrabProtocol.Upload cancelUpload(SandcrabProtocol.Reference reference) {
        SandcrabProtocol.Upload upload = request(
                "POST",
                uploadPath(reference) + "/cancel",
                new SandcrabProtocol.Empty(),
                SandcrabProtocol.Upload.class,
                200);
        require(reference.equals(upload.reference()), "Native upload cancellation changed its identity or epoch");
        return upload;
    }

    /** Ends replay protection; the consumer must reconcile uncertain publication if its data will be retained. */
    void forget(SandcrabProtocol.Upload upload) {
        require(upload.forgettable(), "An active or uncontained upload cannot be forgotten");
        request(
                "POST",
                uploadPath(upload.reference()) + "/forget",
                new SandcrabProtocol.Empty(),
                SandcrabProtocol.Forgotten.class,
                200);
    }

    private static void manifest(SandcrabProtocol.UploadPlan plan, SandcrabProtocol.Upload actual) {
        require(
                plan.reference().equals(actual.reference()) && plan.sessionId().equals(actual.sessionId()),
                "Native upload reply changed its identity or session");
        require(
                plan.path().equals(actual.path())
                        && plan.format().equals(actual.format())
                        && plan.bytes() == actual.bytes()
                        && plan.sha256().equals(actual.sha256()),
                "Native upload reply changed its content manifest");
    }

    private static String sessionPath(SandcrabProtocol.Reference reference) {
        return "/v1/sessions/" + reference.id();
    }

    private static String uploadPath(SandcrabProtocol.Reference reference) {
        return "/v1/uploads/" + reference.id();
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
        return exchange(method, path, "application/json", body, REQUEST_TIMEOUT, type, accepted);
    }

    private <T> T exchange(
            String method,
            String path,
            String contentType,
            byte[] body,
            Duration timeout,
            Class<T> type,
            int... accepted) {
        return http.exchange(method, path, contentType, body, timeout, Duration.ZERO, (head, input) -> {
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
