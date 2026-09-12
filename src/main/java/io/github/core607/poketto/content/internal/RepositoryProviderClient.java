package io.github.core607.poketto.content.internal;

import static io.github.core607.poketto.content.RepositoryConnectionException.Code.PERMISSION_DENIED;
import static io.github.core607.poketto.content.RepositoryConnectionException.Code.REPOSITORY_CHANGED;
import static io.github.core607.poketto.content.RepositoryConnectionException.Code.UNAVAILABLE;

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
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new RepositoryConnectionException(PERMISSION_DENIED);
            }
            if (response.statusCode() != 200) {
                throw new RepositoryConnectionException(UNAVAILABLE);
            }
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
            boolean github = requested.provider().equals("github");
            JsonNode fields = ProviderValues.object(data);
            // Identity is settled before anything else is read, so a reply about another
            // repository is reported as that and not as a malformed answer.
            String id = ProviderValues.identifier(fields, github ? "[1-9][0-9]{0,39}" : "[a-zA-Z0-9_-]{1,128}");
            String path = ProviderValues.text(fields, github ? "full_name" : "path");
            RepositoryCoordinates canonical =
                    RepositoryCoordinates.parse("https://" + (github ? "github.com" : "cnb.cool") + "/" + path);
            if (!canonical.canonicalUri().equals(requested.canonicalUri())) {
                throw new RepositoryConnectionException(REPOSITORY_CHANGED);
            }
            Described described = github ? GitHubRepository.of(id, path, fields) : CnbRepository.of(id, path, fields);
            if (!described.usable()) {
                throw new RepositoryConnectionException(UNAVAILABLE);
            }
            if (!described.writable()) {
                throw new RepositoryConnectionException(PERMISSION_DENIED);
            }
            return new Metadata(canonical, requested.provider() + ":" + id, described.privateRepository());
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

    /**
     * What a provider says about one repository. Each provider answers with its own field names
     * and its own spelling of the same three facts, so each has its own record and both are read
     * strictly: a field of the wrong type is a malformed answer, not a value to coerce.
     *
     * <p>An absent permission is not an error. It means the account cannot write, which is a denial
     * rather than an unusable provider, so the distinction is kept in {@link #writable()}.
     */
    sealed interface Described permits GitHubRepository, CnbRepository {
        String id();

        String path();

        boolean privateRepository();

        /** The repository still accepts work at all. */
        boolean usable();

        /** This account may push to it. */
        boolean writable();
    }

    record GitHubRepository(
            String id, String path, boolean privateRepository, boolean archived, boolean disabled, boolean push)
            implements Described {
        static GitHubRepository of(String id, String path, JsonNode fields) {
            return new GitHubRepository(
                    id,
                    path,
                    ProviderValues.flag(fields, "private"),
                    ProviderValues.flag(fields, "archived"),
                    ProviderValues.flag(fields, "disabled"),
                    fields.path("permissions").path("push").asBoolean(false));
        }

        @Override
        public boolean usable() {
            return !archived && !disabled;
        }

        @Override
        public boolean writable() {
            return push;
        }
    }

    record CnbRepository(String id, String path, String visibility, int status, boolean frozen, String access)
            implements Described {
        private static final Set<String> VISIBILITY = Set.of("Private", "Public", "Secret");
        private static final Set<String> WRITERS = Set.of("Developer", "Master", "Owner");

        CnbRepository {
            if (!VISIBILITY.contains(visibility)) {
                throw new IllegalArgumentException("repository visibility must be one of " + VISIBILITY);
            }
        }

        static CnbRepository of(String id, String path, JsonNode fields) {
            return new CnbRepository(
                    id,
                    path,
                    ProviderValues.text(fields, "visibility_level"),
                    ProviderValues.number(fields, "status"),
                    ProviderValues.flag(fields, "freeze"),
                    fields.path("access").asText());
        }

        @Override
        public boolean privateRepository() {
            return !visibility.equals("Public");
        }

        @Override
        public boolean usable() {
            return status == 0 && !frozen;
        }

        @Override
        public boolean writable() {
            return WRITERS.contains(access);
        }
    }

    /** Strict readers. A provider answer is machine output; a wrong type means a wrong answer. */
    private static final class ProviderValues {
        private ProviderValues() {}

        static JsonNode object(JsonNode data) {
            if (data == null || !data.isObject()) {
                throw new IllegalArgumentException("provider metadata must be a JSON object");
            }
            return data;
        }

        /** Both providers answer with their own identifier shape; GitHub's arrives as a number. */
        static String identifier(JsonNode fields, String shape) {
            String id = fields.path("id").asText();
            if (!id.matches(shape)) {
                throw new IllegalArgumentException("provider repository id does not match " + shape);
            }
            return id;
        }

        static String text(JsonNode fields, String field) {
            return fields.path(field).asText();
        }

        static boolean flag(JsonNode fields, String field) {
            JsonNode value = fields.path(field);
            if (!value.isBoolean()) {
                throw new IllegalArgumentException(field + " must be a boolean");
            }
            return value.booleanValue();
        }

        static int number(JsonNode fields, String field) {
            JsonNode value = fields.path(field);
            if (!value.isInt()) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return value.intValue();
        }
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
