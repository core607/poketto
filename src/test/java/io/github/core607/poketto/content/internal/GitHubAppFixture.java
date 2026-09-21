package io.github.core607.poketto.content.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/** Ordered provider responses also make an accidental retry visible in the request history. */
final class GitHubAppFixture implements AutoCloseable {
    private final HttpServer server;
    private final LinkedBlockingQueue<Response> responses = new LinkedBlockingQueue<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final GitHubAppHttp http;

    GitHubAppFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        http = new GitHubAppHttp(origin, origin, Duration.ofSeconds(2), host -> {});
    }

    void reply(int status, String body) {
        responses.add(new Response(status, body));
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Response response = responses.poll();
            if (response == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            if (response.status() == 0) {
                // Send an incomplete body after headers: the mutation may already have happened.
                exchange.sendResponseHeaders(201, 100);
                exchange.getResponseBody().write('{');
                return;
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        http.close();
        server.stop(0);
    }

    record Request(String method, String path, String authorization, String body) {}

    private record Response(int status, String body) {}
}
