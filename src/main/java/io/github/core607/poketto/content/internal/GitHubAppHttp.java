package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositoryConnectionException;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-origin provider transport. Status interpretation and mutation recovery belong to its caller. */
final class GitHubAppHttp implements AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private final URI api;
    private final URI oauth;
    private final Duration timeout;
    private final DestinationCheck destinations;
    private final HttpClient http;
    private final Semaphore admission = new Semaphore(4);
    private final AtomicBoolean closed = new AtomicBoolean();

    GitHubAppHttp() {
        this(
                URI.create("https://api.github.com/"),
                URI.create("https://github.com/"),
                Duration.ofSeconds(15),
                PublicNetworkDestination::requirePublic);
    }

    /** Alternate origins and DNS checks are fixture-only; production exposes no endpoint setting. */
    GitHubAppHttp(URI api, URI oauth, Duration timeout, DestinationCheck destinations) {
        if (!validTimeout(timeout)) {
            throw new IllegalArgumentException("GitHub HTTP timeout must be positive and at most 30 seconds");
        }
        this.api = api;
        this.oauth = oauth;
        this.timeout = timeout;
        this.destinations = destinations;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new DirectProxy())
                .build();
    }

    Reply getApi(String path, String bearer) throws IOException {
        requireBearer(bearer);
        return exchange(api, path, bearer, null, "application/json");
    }

    Reply postApi(String path, String bearer, byte[] body) throws IOException {
        requireBearer(bearer);
        return exchange(
                api, path, bearer, Objects.requireNonNull(body, "GitHub API body is required"), "application/json");
    }

    Reply exchangeOAuth(byte[] form) throws IOException {
        return exchange(
                oauth,
                "/login/oauth/access_token",
                null,
                Objects.requireNonNull(form, "GitHub OAuth form is required"),
                "application/x-www-form-urlencoded");
    }

    private Reply exchange(URI origin, String path, String bearer, byte[] body, String contentType) throws IOException {
        URI destination = destination(origin, path);
        if (body != null && body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("GitHub HTTP request exceeds its byte limit");
        }
        if (closed.get()) {
            throw new IOException("GitHub HTTP client is closed");
        }
        if (!admission.tryAcquire()) {
            throw new RepositoryConnectionException(RepositoryConnectionException.Code.BUSY);
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            destinations.requirePublic(destination.getHost());
            var builder = HttpRequest.newBuilder(destination)
                    .timeout(timeout)
                    .header("User-Agent", "Poketto")
                    .header("Accept", "application/json")
                    .header("X-GitHub-Api-Version", "2026-03-10");
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            }
            pending = http.sendAsync(builder.build(), info -> new BoundedProviderBody(MAX_RESPONSE_BYTES));
            HttpResponse<byte[]> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new Reply(response.statusCode(), response.body());
        } catch (TimeoutException failure) {
            throw new IOException("GitHub HTTP response deadline exceeded", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub HTTP request interrupted", failure);
        } catch (ExecutionException failure) {
            throw new IOException("GitHub HTTP exchange failed", failure.getCause());
        } catch (CancellationException failure) {
            throw new IOException("GitHub HTTP request cancelled", failure);
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
            admission.release();
        }
    }

    private static URI destination(URI origin, String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("GitHub HTTP path must be relative to its fixed origin");
        }
        URI resolved = origin.resolve(path);
        if (!sameOrigin(origin, resolved)) {
            throw new IllegalArgumentException("GitHub HTTP destination must stay on its fixed origin");
        }
        return resolved;
    }

    private static boolean sameOrigin(URI origin, URI candidate) {
        return origin.getScheme().equals(candidate.getScheme())
                && origin.getHost().equals(candidate.getHost())
                && origin.getPort() == candidate.getPort()
                && candidate.getUserInfo() == null
                && candidate.getFragment() == null;
    }

    private static void requireBearer(String bearer) {
        if (!validBearer(bearer)) {
            throw new IllegalArgumentException("GitHub bearer credential is missing or malformed");
        }
    }

    private static boolean validBearer(String value) {
        return value != null && value.length() <= 16_384 && value.matches("[A-Za-z0-9._~+/-]+={0,2}");
    }

    private static boolean validTimeout(Duration value) {
        return value != null && value.isPositive() && value.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            http.shutdownNow();
            http.close();
        }
    }

    record Reply(int status, byte[] body) {
        Reply {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "GitHubHttpReply[status=" + status + "]";
        }
    }

    @FunctionalInterface
    interface DestinationCheck {
        void requirePublic(String host) throws IOException;
    }

    private static final class DirectProxy extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
    }
}
