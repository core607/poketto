package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One authenticated Unix HTTP exchange, with bounded request buffers and no replay. */
final class SandboxHttp {
    private static final Logger log = LoggerFactory.getLogger(SandboxHttp.class);
    private static final int MAX_HEAD = 8192;
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024;
    private final Path socket;
    private final Runnable verifySocket;
    private final Consumer<SocketChannel> verifyPeer;

    SandboxHttp(Path socket, Runnable verifySocket, Consumer<SocketChannel> verifyPeer) {
        this.socket = socket;
        this.verifySocket = verifySocket;
        this.verifyPeer = verifyPeer;
    }

    record Head(int status, String contentType, long length) {}

    /** Reads while the connection is owned by exchange; the caller must bound and validate its body. */
    @FunctionalInterface
    interface Reader<T> {
        T read(Head head, InputStream body) throws IOException;
    }

    <T> T exchange(
            String method,
            String path,
            String contentType,
            byte[] body,
            Duration timeout,
            Duration idle,
            Reader<T> reader) {
        byte[] bytes = body == null ? new byte[0] : body;
        require(bytes.length <= MAX_REQUEST_BYTES);
        ByteBuffer header = requestHead(method, path, contentType, bytes.length);
        var packet = ByteBuffer.allocate(header.remaining() + bytes.length)
                .put(header)
                .put(bytes)
                .flip();
        var request = new MemoryRequest(packet);
        return exchange(request, timeout, idle, reader);
    }

    /** Streams a caller-owned local source once; the caller closes it, including after an uncertain write. */
    <T> T upload(String path, InputStream body, long length, Duration timeout, Reader<T> reader) {
        require(length >= 0 && length <= MAX_UPLOAD_BYTES);
        var request = new StreamRequest(requestHead("PUT", path, "application/octet-stream", length), body, length);
        return exchange(request, timeout, Duration.ZERO, reader);
    }

    private <T> T exchange(Request request, Duration timeout, Duration idle, Reader<T> reader) {
        try {
            verifySocket.run();
        } catch (WorkerUnavailableException failure) {
            throw new WorkerUnavailableException(failure, false);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        String phase = "connect";
        boolean reading = false;
        boolean writing = false;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) {
                    ready(channel, selector, SelectionKey.OP_CONNECT, deadline);
                }
            }
            phase = "peer-verification";
            verifyPeer.accept(channel);
            phase = "request-write";
            writing = true;
            writeRequest(channel, selector, request, deadline);
            phase = "response-header";
            Response response = response(channel, selector, deadline, idle.toNanos());
            phase = "response-body";
            reading = true;
            return reader.read(response.head(), response.body());
        } catch (IOException | WorkerUnavailableException exception) {
            throw unavailable(phase, exception, writing);
        } catch (RuntimeException exception) {
            if (reading) {
                // A reader's own refusal, such as an invalid selection, keeps its meaning.
                throw exception;
            }
            throw unavailable(phase, exception, writing);
        }
    }

    private static WorkerUnavailableException unavailable(String phase, Exception exception, boolean writing) {
        log.warn("Sandbox exchange failed during {}", phase, exception);
        return writing && exception instanceof WorkerUnavailableException unavailable
                ? unavailable
                : new WorkerUnavailableException(exception, writing);
    }

    private sealed interface Request permits MemoryRequest, StreamRequest {}

    private record MemoryRequest(ByteBuffer packet) implements Request {}

    private record StreamRequest(ByteBuffer head, InputStream input, long length) implements Request {}

    private static void writeRequest(SocketChannel channel, Selector selector, Request request, long deadline)
            throws IOException {
        switch (request) {
            case MemoryRequest memory -> write(channel, selector, memory.packet(), deadline);
            case StreamRequest stream -> {
                write(channel, selector, stream.head(), deadline);
                writeStream(channel, selector, stream, deadline);
            }
        }
    }

    private static void writeStream(SocketChannel channel, Selector selector, StreamRequest body, long deadline)
            throws IOException {
        if (body.length() == 0) {
            requireSourceEnded(body.input());
            return;
        }
        byte[] buffer = new byte[(int) Math.min(64 * 1024, body.length())];
        long remaining = body.length();
        while (remaining > 0) {
            deadline(deadline);
            int read = body.input().read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read <= 0) {
                throw new IOException("Sandbox request source ended before its declared length");
            }
            remaining -= read;
            if (remaining == 0) {
                requireSourceEnded(body.input());
            }
            write(channel, selector, ByteBuffer.wrap(buffer, 0, read), deadline);
        }
    }

    private static void requireSourceEnded(InputStream body) throws IOException {
        if (body.read() != -1) {
            throw new IOException("Sandbox request source exceeds its declared length");
        }
    }

    private static void write(SocketChannel channel, Selector selector, ByteBuffer bytes, long deadline)
            throws IOException {
        while (bytes.hasRemaining()) {
            deadline(deadline);
            if (channel.write(bytes) == 0) {
                ready(channel, selector, SelectionKey.OP_WRITE, deadline);
            }
        }
    }

    private static ByteBuffer requestHead(String method, String path, String contentType, long length) {
        require(method.matches("GET|POST|PUT|DELETE"));
        require(path.startsWith("/") && path.chars().allMatch(c -> c > 32 && c < 127));
        require(contentType.equals("application/json") || contentType.equals("application/octet-stream"));
        byte[] head = (method + " " + path + " HTTP/1.1\r\nHost: sandcrab\r\n"
                        + (length > 0 || method.equals("POST") || method.equals("PUT")
                                ? "Content-Type: " + contentType + "\r\nContent-Length: " + length + "\r\n"
                                : "")
                        + "Connection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        require(head.length <= MAX_HEAD);
        return ByteBuffer.wrap(head);
    }

    private record Response(Head head, InputStream body) {}

    /** The response head and its declared body, beginning with the bytes that arrived with the head. */
    private static Response response(SocketChannel channel, Selector selector, long deadline, long idle)
            throws IOException {
        ByteBuffer head = ByteBuffer.allocate(MAX_HEAD + 4);
        int end;
        while ((end = headEnd(head)) < 0) {
            require(head.hasRemaining());
            var input = new ChannelInput(channel, selector, deadline, 0, head.remaining(), null, 0, 0);
            int read = input.read(head.array(), head.position(), head.remaining());
            if (read < 0) {
                throw new IOException("incomplete worker response");
            }
            head.position(head.position() + read);
        }
        Head parsed = head(new String(head.array(), 0, end, StandardCharsets.US_ASCII));
        int received = head.position() - end - 4;
        require(received <= parsed.length());
        return new Response(
                parsed,
                new ChannelInput(channel, selector, deadline, idle, parsed.length(), head.array(), end + 4, received));
    }

    /** The offset of the blank line ending the response head, or -1 before it has arrived. */
    private static int headEnd(ByteBuffer response) {
        byte[] bytes = response.array();
        for (int index = 0; index + 3 < response.position(); index++) {
            if (bytes[index] == '\r'
                    && bytes[index + 1] == '\n'
                    && bytes[index + 2] == '\r'
                    && bytes[index + 3] == '\n') {
                return index;
            }
        }
        return -1;
    }

    /** A final status and exactly one Content-Length; streamed or ambiguous framing is refused. */
    private static Head head(String head) {
        String[] lines = head.split("\r\n", -1);
        String[] statusLine = lines[0].split(" ", 3);
        require(statusLine.length == 3 && statusLine[0].equals("HTTP/1.1"));
        require(statusLine[1].matches("[2-5][0-9][0-9]"));
        int status = Integer.parseInt(statusLine[1]);
        Long length = null;
        String type = null;
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            require(colon > 0);
            String name = lines[index].substring(0, colon);
            String value = lines[index].substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Content-Length")) {
                require(length == null
                        && !value.isEmpty()
                        && value.length() <= 12
                        && value.chars().allMatch(Character::isDigit));
                length = Long.parseLong(value);
            } else if (name.equalsIgnoreCase("Content-Type")) {
                require(type == null);
                type = value;
            } else {
                require(!name.equalsIgnoreCase("Transfer-Encoding"));
            }
        }
        require(length != null && (length == 0 || type != null));
        return new Head(status, type, length);
    }

    /**
     * The declared body: first the bytes that arrived with the head, then the socket. Each read
     * waits until the deadline, which a positive {@code idle} moves forward after every read.
     */
    private static final class ChannelInput extends InputStream {
        private final SocketChannel channel;
        private final Selector selector;
        private final long idle;
        private final byte[] prefix;
        private long deadline;
        private long remaining;
        private int prefixOffset;
        private int prefixLeft;

        ChannelInput(
                SocketChannel channel,
                Selector selector,
                long deadline,
                long idle,
                long remaining,
                byte[] prefix,
                int prefixOffset,
                int prefixLeft) {
            this.channel = channel;
            this.selector = selector;
            this.deadline = deadline;
            this.idle = idle;
            this.remaining = remaining;
            this.prefix = prefix;
            this.prefixOffset = prefixOffset;
            this.prefixLeft = prefixLeft;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            if (length == 0) {
                return 0;
            }
            int wanted = (int) Math.min(length, remaining);
            int read;
            if (prefixLeft > 0) {
                read = Math.min(wanted, prefixLeft);
                System.arraycopy(prefix, prefixOffset, bytes, offset, read);
                prefixOffset += read;
                prefixLeft -= read;
            } else {
                ByteBuffer target = ByteBuffer.wrap(bytes, offset, wanted);
                while (true) {
                    deadline(deadline);
                    read = channel.read(target);
                    if (read < 0) {
                        throw new IOException("incomplete worker response");
                    }
                    if (read > 0) {
                        break;
                    }
                    ready(channel, selector, SelectionKey.OP_READ, deadline);
                }
                if (idle > 0) {
                    deadline = System.nanoTime() + idle;
                }
            }
            remaining -= read;
            return read;
        }
    }

    private static void ready(SocketChannel channel, Selector selector, int operation, long deadline)
            throws IOException {
        deadline(deadline);
        channel.register(selector, operation);
        long millis = Math.max(1, Math.min(1000, (deadline - System.nanoTime()) / 1_000_000));
        selector.select(millis);
        selector.selectedKeys().clear();
    }

    private static void deadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw new IOException("worker exchange deadline elapsed");
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new WorkerUnavailableException();
        }
    }
}
