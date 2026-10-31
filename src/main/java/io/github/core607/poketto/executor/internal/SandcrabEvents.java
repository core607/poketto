package io.github.core607.poketto.executor.internal;

import static io.github.core607.poketto.executor.internal.SandcrabEventStream.require;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Observes one retained native command; disconnection never resubmits a command or a host effect. */
final class SandcrabEvents {
    private static final Duration IDLE = Duration.ofSeconds(5);
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(5);
    private final SandboxHttp http;

    SandcrabEvents(SandboxHttp http) {
        this.http = http;
    }

    /** A daemon-allocated handle scoped to the observed command, distinct from the guest request ID. */
    static final class Call {
        private final SandcrabProtocol.Reference run;
        private final SandcrabEventProtocol.CallCursor cursor;
        private final SandcrabEventProtocol.CallRequest request;

        private Call(
                SandcrabProtocol.Reference run,
                SandcrabEventProtocol.CallCursor cursor,
                SandcrabEventProtocol.CallRequest request) {
            this.run = run;
            this.cursor = cursor;
            this.request = request;
        }

        SandcrabProtocol.Reference run() {
            return run;
        }

        SandcrabEventProtocol.CallCursor cursor() {
            return cursor;
        }

        String id() {
            return request.id();
        }

        String requestId() {
            return request.requestId();
        }

        byte[] bytes() {
            return request.bytes();
        }
    }

    record Output(String stream, SandcrabEventProtocol.OutputCursor cursor, byte[] bytes) {
        Output {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** The handler owns authorization and effect reconciliation; its returned bytes are published once. */
    SandcrabEventProtocol.Run observeCalls(
            SandcrabProtocol.Reference run, Duration timeout, Function<Call, byte[]> handler) {
        Objects.requireNonNull(handler, "host-call handler must be present");
        long deadline = deadline(timeout);
        return observeCalls(run, null, timeout, call -> {
            byte[] bytes = handler.apply(call);
            Duration budget = remaining(deadline);
            reply(call, bytes, budget.compareTo(REPLY_TIMEOUT) < 0 ? budget : REPLY_TIMEOUT);
        });
    }

    /** A cursor is an explicit observation choice. Delivered IDs never invoke this observer twice. */
    SandcrabEventProtocol.Run observeCalls(
            SandcrabProtocol.Reference run,
            SandcrabEventProtocol.CallCursor after,
            Duration timeout,
            Consumer<Call> observer) {
        Objects.requireNonNull(observer, "host-call observer must be present");
        long deadline = deadline(timeout);
        return http.events(
                path(run) + "/calls/events", after == null ? null : after.text(), timeout, IDLE, (head, body) -> {
                    eventHead(head, body);
                    return calls(new SandcrabEventStream(body, 131_072), run, after, observer, deadline);
                });
    }

    SandcrabEventProtocol.CallInventory inventory(SandcrabProtocol.Reference run) {
        return http.exchange(
                "GET", path(run) + "/calls", "application/json", null, REPLY_TIMEOUT, Duration.ZERO, (head, body) -> {
                    SandcrabEventProtocol.CallInventory inventory =
                            json(head, body, SandcrabEventProtocol.CallInventory.class);
                    require(
                            inventory.reference().equals(run),
                            "Native call inventory changed its run identity or server epoch");
                    return inventory;
                });
    }

    /** Output bytes are exact, may split UTF-8 code points, and are never accumulated by this helper. */
    SandcrabEventProtocol.Run observe(
            SandcrabProtocol.Reference run,
            SandcrabEventProtocol.OutputCursor after,
            Duration timeout,
            Consumer<Output> observer) {
        Objects.requireNonNull(observer, "output observer must be present");
        long deadline = deadline(timeout);
        return http.events(path(run) + "/events", after == null ? null : after.text(), timeout, IDLE, (head, body) -> {
            eventHead(head, body);
            var stream = new SandcrabEventStream(body, 16_384);
            SandcrabEventProtocol.OutputCursor cursor = initial(stream.next(), run, after);
            return output(stream, run, cursor, observer, deadline);
        });
    }

    /** Explicit repeated replies are safe only after caller reconciliation; this method never retries. */
    SandcrabEventProtocol.CallInfo reply(Call call, byte[] bytes) {
        return reply(call, bytes, REPLY_TIMEOUT);
    }

    private SandcrabEventProtocol.CallInfo reply(Call call, byte[] bytes, Duration timeout) {
        Objects.requireNonNull(call, "observed host call must be present");
        Objects.requireNonNull(bytes, "host-call reply bytes must be present");
        ProtocolValues.inRange(bytes.length, 0, SandcrabEventProtocol.CALL_BYTES, "host-call reply bytes");
        byte[] payload = bytes.clone();
        var request = new SandcrabEventProtocol.Reply(Base64.getEncoder().encodeToString(payload));
        return http.exchange(
                "POST",
                path(call.run()) + "/calls/" + call.id() + "/reply",
                "application/json",
                SandcrabProtocol.write(request),
                timeout,
                Duration.ZERO,
                (head, input) -> {
                    SandcrabEventProtocol.CallInfo receipt = json(head, input, SandcrabEventProtocol.CallInfo.class);
                    require(receipt.id().equals(call.id()), "Native reply changed its call ID");
                    require(receipt.requestId().equals(call.requestId()), "Native reply changed its guest request ID");
                    require(
                            receipt.sequence() == call.cursor().sequence(),
                            "Native reply changed its request sequence");
                    require(
                            receipt.requestSha256().equals(call.request.requestSha256()),
                            "Native reply changed its request digest");
                    require(receipt.state().equals("replied"), "Native reply publication is unconfirmed");
                    require(
                            SandcrabProtocol.sha256(payload).equals(receipt.replySha256()),
                            "Native reply digest differs");
                    return receipt;
                });
    }

    private static SandcrabEventProtocol.OutputCursor initial(
            SandcrabEventStream.Frame frame, SandcrabProtocol.Reference run, SandcrabEventProtocol.OutputCursor after)
            throws IOException {
        require(frame.kind().equals("state"), "Native output preceded its initial state");
        SandcrabEventProtocol.Run state = SandcrabProtocol.read(frame.data(), SandcrabEventProtocol.Run.class);
        require(state.reference().equals(run), "Native event changed its run identity or server epoch");
        SandcrabEventProtocol.OutputCursor cursor = outputCursor(frame.id());
        require(
                after == null ? cursor.stdout() == 0 && cursor.stderr() == 0 : cursor.equals(after),
                "Native output cursor differs from its requested position");
        return cursor;
    }

    private static SandcrabEventProtocol.Run output(
            SandcrabEventStream stream,
            SandcrabProtocol.Reference run,
            SandcrabEventProtocol.OutputCursor cursor,
            Consumer<Output> observer,
            long deadline)
            throws IOException {
        while (true) {
            active(deadline);
            SandcrabEventStream.Frame frame = stream.next();
            SandcrabEventProtocol.OutputCursor next = outputCursor(frame.id());
            require(cursor.incarnation().equals(next.incarnation()), "Native output incarnation changed");
            if (frame.kind().equals("stdout") || frame.kind().equals("stderr")) {
                SandcrabEventProtocol.Output block =
                        SandcrabProtocol.read(frame.data(), SandcrabEventProtocol.Output.class);
                byte[] bytes = block.bytes();
                boolean stdout = frame.kind().equals("stdout");
                require(block.offset() == (stdout ? cursor.stdout() : cursor.stderr()), "Native output offset differs");
                require(
                        next.stdout() == cursor.stdout() + (stdout ? bytes.length : 0)
                                && next.stderr() == cursor.stderr() + (stdout ? 0 : bytes.length),
                        "Native output cursor differs from delivered bytes");
                observer.accept(new Output(frame.kind(), next, bytes));
            } else if (frame.kind().equals("result")) {
                SandcrabEventProtocol.Run result = SandcrabProtocol.read(frame.data(), SandcrabEventProtocol.Run.class);
                require(
                        result.reference().equals(run) && result.terminal(),
                        "Native output result is not confirmed for this run");
                require(cursor.equals(next), "Native terminal output cursor changed");
                require(
                        Objects.equals(result.stdoutBytes(), next.stdout())
                                && Objects.equals(result.stderrBytes(), next.stderr()),
                        "Native output result lengths differ");
                return result;
            } else {
                failed(frame);
            }
            cursor = next;
        }
    }

    private static SandcrabEventProtocol.Run calls(
            SandcrabEventStream stream,
            SandcrabProtocol.Reference run,
            SandcrabEventProtocol.CallCursor after,
            Consumer<Call> observer,
            long deadline)
            throws IOException {
        SandcrabEventStream.Frame initial = stream.next();
        require(initial.kind().equals("state"), "Native calls preceded their initial state");
        SandcrabEventProtocol.CallCursor cursor = callCursor(initial.id());
        require(
                after == null ? cursor.sequence() == 0 : cursor.equals(after),
                "Native call cursor differs from its requested position");
        SandcrabEventProtocol.CallState state =
                SandcrabProtocol.read(initial.data(), SandcrabEventProtocol.CallState.class);
        require(state.reference().equals(run), "Native call stream changed its run identity or server epoch");
        require(cursor.sequence() <= state.calls().calls().size(), "Native call cursor exceeds its initial inventory");
        Set<String> ids = new HashSet<>();
        Set<String> requests = new HashSet<>();
        while (true) {
            active(deadline);
            SandcrabEventStream.Frame frame = stream.next();
            SandcrabEventProtocol.CallCursor next = callCursor(frame.id());
            require(cursor.incarnation().equals(next.incarnation()), "Native call incarnation changed");
            require(next.sequence() >= cursor.sequence(), "Native call cursor moved backwards");
            if (frame.kind().equals("call")) {
                SandcrabEventProtocol.CallRequest request =
                        SandcrabProtocol.read(frame.data(), SandcrabEventProtocol.CallRequest.class);
                require(
                        next.sequence() > cursor.sequence() && next.sequence() == request.sequence(),
                        "Native call sequence differs");
                require(
                        ids.add(request.id()) && requests.add(request.requestId()),
                        "Native call identity was delivered twice");
                active(deadline);
                observer.accept(new Call(run, next, request));
            } else if (frame.kind().equals("result")) {
                SandcrabEventProtocol.CallResult result =
                        SandcrabProtocol.read(frame.data(), SandcrabEventProtocol.CallResult.class);
                require(
                        result.run().reference().equals(run),
                        "Native call result changed its run identity or server epoch");
                require(
                        next.sequence() == result.calls().calls().size(),
                        "Native call result cursor differs from its inventory");
                return result.run();
            } else {
                failed(frame);
            }
            cursor = next;
        }
    }

    private static void failed(SandcrabEventStream.Frame frame) throws IOException {
        if (frame.kind().equals("error")) {
            SandcrabProtocol.Error error = SandcrabProtocol.read(frame.data(), SandcrabProtocol.Error.class);
            throw new IOException("Native observation ended without confirmed containment: " + error.code());
        }
        throw new IOException("Unexpected native event kind");
    }

    private static SandcrabEventProtocol.OutputCursor outputCursor(String value) throws IOException {
        try {
            return SandcrabEventProtocol.OutputCursor.parse(value);
        } catch (IllegalArgumentException invalid) {
            // UUID parsing can quote an untrusted cursor; retain only the protocol failure.
            throw new IOException("Invalid native output cursor");
        }
    }

    private static SandcrabEventProtocol.CallCursor callCursor(String value) throws IOException {
        try {
            return SandcrabEventProtocol.CallCursor.parse(value);
        } catch (IllegalArgumentException invalid) {
            // UUID parsing can quote an untrusted cursor; retain only the protocol failure.
            throw new IOException("Invalid native call cursor");
        }
    }

    private static void eventHead(SandboxHttp.Head head, InputStream body) throws IOException {
        if (head.status() != 200) {
            json(head, body, SandcrabProtocol.Empty.class);
        }
        require(head.status() == 200 && head.length() == -1, "Native event response is not chunked success");
        require("text/event-stream".equals(head.contentType()), "Native event response has the wrong media type");
    }

    private static <T> T json(SandboxHttp.Head head, InputStream body, Class<T> type) throws IOException {
        require(head.length() > 0 && head.length() <= 131_072, "Native event JSON exceeds its bound");
        require("application/json".equals(head.contentType()), "Native event response is not JSON");
        byte[] bytes = body.readNBytes((int) head.length());
        require(bytes.length == head.length(), "Native event JSON was truncated");
        if (head.status() == 200) {
            return SandcrabProtocol.read(bytes, type);
        }
        SandcrabProtocol.Failure failure = SandcrabProtocol.read(bytes, SandcrabProtocol.Failure.class);
        if (head.status() >= 400 && head.status() < 500) {
            throw new SandcrabClient.Rejected(head.status(), failure.error().code());
        }
        throw new IOException("Native event request failed: " + failure.error().code());
    }

    private static String path(SandcrabProtocol.Reference run) {
        Objects.requireNonNull(run, "observed run must be present");
        return "/v1/runs/" + run.id();
    }

    private static long deadline(Duration timeout) {
        Objects.requireNonNull(timeout, "observation timeout must be present");
        ProtocolValues.inRange(timeout.toMillis(), 1, 350_000, "observation milliseconds");
        return System.nanoTime() + timeout.toNanos();
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new WorkerUnavailableException(new IOException("Native observation deadline elapsed"));
        }
        return Duration.ofNanos(nanos);
    }

    private static void active(long deadline) throws IOException {
        require(!Thread.currentThread().isInterrupted(), "Native observation was interrupted");
        require(System.nanoTime() - deadline < 0, "Native observation deadline elapsed");
    }
}
