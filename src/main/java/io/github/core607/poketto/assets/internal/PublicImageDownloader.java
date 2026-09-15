package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ManagedBlobStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;

/** Fetches original bytes without cookies, credentials, proxies or unvalidated DNS connections. */
public final class PublicImageDownloader implements AutoCloseable {
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("image-download-deadline").factory());
    private final CloseableHttpClient client;
    private final PublicDns dns = new PublicDns();

    public PublicImageDownloader() {
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom()
                                .setMaxHeaderCount(64)
                                .setMaxLineLength(16384)
                                .build())
                        .build())
                .setMaxConnTotal(4)
                .setMaxConnPerRoute(4)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();
        client = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    public byte[] download(String url) throws IOException {
        var active = new AtomicReference<HttpGet>();
        var expired = new AtomicBoolean();
        var deadline = deadlines.schedule(
                () -> {
                    expired.set(true);
                    HttpGet request = active.get();
                    if (request != null) {
                        request.cancel();
                    }
                },
                30,
                TimeUnit.SECONDS);
        try {
            URI target = validateUri(url);
            for (int hop = 0; hop <= 3; hop++) {
                // Literal addresses may bypass the client's resolver; validate them here too.
                dns.resolve(target.getHost());
                var request = new HttpGet(target);
                request.setHeader("Accept", "image/*, application/octet-stream;q=0.5");
                active.set(request);
                if (expired.get()) {
                    throw new IOException("image download deadline exceeded");
                }
                Hop result = client.execute(request, PublicImageDownloader::readResponse);
                if (expired.get()) {
                    throw new IOException("image download deadline exceeded");
                }
                if (result.bytes() != null) {
                    return result.bytes();
                }
                target = validateUri(target.resolve(result.location()).toString());
            }
            throw new IOException("image download redirect limit exceeded");
        } finally {
            deadline.cancel(false);
        }
    }

    public static URI validateUri(String value) {
        if (value == null || value.length() > 16384) {
            throw new IllegalArgumentException("image URL must be a bounded HTTPS address");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("image URL is malformed", invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("image URL must use HTTPS with a valid host");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("image URL must not contain user credentials or fragments");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("image URL must use the default HTTPS port");
        }
        return uri;
    }

    private static Hop readResponse(ClassicHttpResponse response) throws IOException {
        if (REDIRECTS.contains(response.getCode())) {
            var location = response.getFirstHeader("Location");
            if (location == null) {
                throw new IOException("image redirect has no destination");
            }
            return new Hop(null, location.getValue());
        }
        if (response.getCode() != 200 || response.getEntity() == null) {
            throw new IOException("image source did not return a complete file");
        }
        var entity = response.getEntity();
        if (entity.getContentEncoding() != null && !entity.getContentEncoding().equalsIgnoreCase("identity")) {
            throw new IOException("compressed HTTP image responses are unsupported");
        }
        if (entity.getContentLength() > ManagedBlobStore.MAX_UPLOAD_BYTES) {
            throw new AssetStorageException(AssetStorageException.Reason.TOO_LARGE);
        }
        try (var stream = entity.getContent()) {
            byte[] bytes = stream.readNBytes(ManagedBlobStore.MAX_UPLOAD_BYTES + 1);
            if (bytes.length > ManagedBlobStore.MAX_UPLOAD_BYTES) {
                throw new AssetStorageException(AssetStorageException.Reason.TOO_LARGE);
            }
            return new Hop(bytes, null);
        }
    }

    private record Hop(byte[] bytes, String location) {}

    static final class PublicDns implements DnsResolver {
        // A stalled OS lookup must not accumulate unbounded resolver threads or queued work.
        private final ThreadPoolExecutor lookups = new ThreadPoolExecutor(
                0,
                4,
                30,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("image-dns-", 0).factory());

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = lookup(host);
            if (addresses.length == 0) {
                throw new UnknownHostException("image source has no public address");
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new UnknownHostException("image source address is not public");
                }
            }
            return addresses;
        }

        private InetAddress[] lookup(String host) throws UnknownHostException {
            try {
                var pending = lookups.submit(() -> InetAddress.getAllByName(host));
                try {
                    return pending.get(3, TimeUnit.SECONDS);
                } finally {
                    pending.cancel(true);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                var failure = new UnknownHostException("image DNS lookup interrupted");
                failure.initCause(interrupted);
                throw failure;
            } catch (ExecutionException | TimeoutException | RejectedExecutionException unavailable) {
                var failure = new UnknownHostException("image DNS lookup unavailable");
                failure.initCause(unavailable);
                throw failure;
            }
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            return first != 0
                    && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0)
                    && !(first == 198 && (second == 18 || second == 19 || second == 51))
                    && !(first == 203 && second == 0 && Byte.toUnsignedInt(bytes[2]) == 113);
        }
        // Only global unicast; exclude special-use 2001::/23 and 6to4's embedded IPv4 destinations.
        return bytes.length == 16
                && (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x01 && Byte.toUnsignedInt(bytes[2]) < 2)
                && !(first == 0x20 && second == 0x02);
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        dns.lookups.shutdownNow();
        client.close();
    }
}
