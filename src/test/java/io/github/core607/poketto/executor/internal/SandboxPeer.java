package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An ordinary local peer; multiple connections make unexpected request replays observable. */
final class SandboxPeer implements AutoCloseable {
    private static final Pattern LENGTH = Pattern.compile("Content-Length: ([0-9]+)\\r\\n");
    private final ServerSocketChannel server;
    private final UnixDomainSocketAddress address;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger requests = new AtomicInteger();
    private final Future<?> task;

    SandboxPeer(Exchange exchange) throws IOException {
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(null);
        address = (UnixDomainSocketAddress) server.getLocalAddress();
        task = executor.submit(() -> {
            while (server.isOpen()) {
                try (SocketChannel channel = server.accept()) {
                    requests.incrementAndGet();
                    exchange.respond(channel);
                } catch (ClosedChannelException closed) {
                    if (server.isOpen()) {
                        throw closed;
                    }
                }
            }
            return null;
        });
    }

    SandboxHttp client(Consumer<SocketChannel> verification) {
        return new SandboxHttp(address.getPath(), () -> {}, verification);
    }

    int requests() {
        return requests.get();
    }

    static Request request(SocketChannel channel) throws IOException {
        InputStream input = Channels.newInputStream(channel);
        String head = head(input);
        Matcher length = LENGTH.matcher(head);
        int bytes = length.find() ? Integer.parseInt(length.group(1)) : 0;
        if (bytes > 1024 * 1024) {
            throw new IOException("Test request exceeded its body limit");
        }
        return new Request(head, input.readNBytes(bytes));
    }

    static void reply(SocketChannel channel, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " Outcome\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length + "\r\n\r\n";
        Channels.newOutputStream(channel).write(header.getBytes(StandardCharsets.US_ASCII));
        Channels.newOutputStream(channel).write(bytes);
    }

    static String head(InputStream input) throws IOException {
        var result = new StringBuilder();
        while (!result.toString().endsWith("\r\n\r\n")) {
            int value = input.read();
            if (value < 0 || result.length() > 8192) {
                throw new IOException("Incomplete test request header");
            }
            result.append((char) value);
        }
        return result.toString();
    }

    record Request(String head, byte[] body) {}

    @FunctionalInterface
    interface Exchange {
        void respond(SocketChannel channel) throws IOException;
    }

    @Override
    public void close() throws Exception {
        try {
            server.close();
            task.get(5, TimeUnit.SECONDS);
        } finally {
            executor.close();
            Files.deleteIfExists(address.getPath());
        }
    }
}
