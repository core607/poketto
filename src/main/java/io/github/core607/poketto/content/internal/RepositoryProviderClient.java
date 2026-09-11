package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.RepositoryConnectionException.Code.*;

import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryCoordinates;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Provider metadata supplies an immutable identity; Git URLs alone cannot identify renamed repositories. */
final class RepositoryProviderClient implements AutoCloseable {
    private static final int MAX_METADATA_BYTES = 128 * 1024;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException error) {}
            })
            .build();

    Metadata read(RepositoryCoordinates coordinates, RepositoryCredentialCipher.Credentials credentials) {
        boolean github = coordinates.provider().equals("github");
        String path = URI.create(coordinates.canonicalUri()).getRawPath();
        URI endpoint = URI.create(github ? "https://api.github.com/repos" + path : "https://api.cnb.cool" + path);
        try {
            PublicNetworkDestination.requirePublic(endpoint.getHost());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", github ? "application/vnd.github+json" : "application/vnd.cnb.api+json")
                    .header("User-Agent", "Poketto")
                    .header("Authorization", "Bearer " + credentials.password())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, info -> new BoundedBody(MAX_METADATA_BYTES));
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new RepositoryConnectionException(PERMISSION_DENIED);
            if (response.statusCode() != 200) throw new RepositoryConnectionException(UNAVAILABLE);
            return parse(coordinates, json.readTree(response.body()));
        } catch (IOException exception) {
            throw new RepositoryConnectionException(UNAVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RepositoryConnectionException(UNAVAILABLE);
        } catch (RepositoryConnectionException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw new RepositoryConnectionException(UNAVAILABLE);
        }
    }

    static Metadata parse(RepositoryCoordinates requested, JsonNode data) {
        try {
            if (data == null || !data.isObject()) throw new IllegalArgumentException();
            String id = data.path("id").asText();
            boolean github = requested.provider().equals("github");
            if (!id.matches(github ? "[1-9][0-9]{0,39}" : "[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException();
            String path = data.path(github ? "full_name" : "path").asText();
            RepositoryCoordinates canonical =
                    RepositoryCoordinates.parse("https://" + (github ? "github.com" : "cnb.cool") + "/" + path);
            if (!canonical.canonicalUri().equals(requested.canonicalUri()))
                throw new RepositoryConnectionException(REPOSITORY_CHANGED);
            boolean privateRepository;
            if (github) {
                if (!data.path("private").isBoolean()
                        || !data.path("archived").isBoolean()
                        || !data.path("disabled").isBoolean()) throw new IllegalArgumentException();
                if (data.path("archived").asBoolean() || data.path("disabled").asBoolean())
                    throw new RepositoryConnectionException(UNAVAILABLE);
                if (!data.path("permissions").path("push").asBoolean(false))
                    throw new RepositoryConnectionException(PERMISSION_DENIED);
                privateRepository = data.path("private").asBoolean();
            } else {
                String visibility = data.path("visibility_level").asText();
                if (!Set.of("Private", "Public", "Secret").contains(visibility)
                        || !data.path("status").isInt()
                        || !data.path("freeze").isBoolean()) throw new IllegalArgumentException();
                if (data.path("status").intValue() != 0 || data.path("freeze").asBoolean())
                    throw new RepositoryConnectionException(UNAVAILABLE);
                if (!Set.of("Developer", "Master", "Owner")
                        .contains(data.path("access").asText()))
                    throw new RepositoryConnectionException(PERMISSION_DENIED);
                privateRepository = !visibility.equals("Public");
            }
            return new Metadata(canonical, requested.provider() + ":" + id, privateRepository);
        } catch (RepositoryConnectionException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw new RepositoryConnectionException(UNAVAILABLE);
        }
    }

    @Override
    public void close() {
        http.close();
    }

    record Metadata(RepositoryCoordinates coordinates, String identity, boolean privateRepository) {
        @Override
        public String toString() {
            return "RepositoryMetadata[redacted]";
        }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int maximum;
        private Flow.Subscription subscription;

        BoundedBody(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - output.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Provider metadata exceeds its limit"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                output.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
