package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Applies accounting and byte/time limits to framework-serialized requests, without protocol codecs. */
final class QaTransport {
    private final ThreadLocal<Dispatch> active = new ThreadLocal<>();
    private final RestClient.Builder rest;

    QaTransport(HttpClient http, Consumer<byte[]> inspect) {
        rest = RestClient.builder()
                .requestFactory((uri, method) -> {
                    var factory = new JdkClientHttpRequestFactory(http);
                    factory.setReadTimeout(active.get().timeout);
                    return factory.createRequest(uri, method);
                })
                .requestInterceptor((request, body, execution) -> {
                    if (body.length > QaPolicy.INPUT_BYTES) {
                        throw new QaException("INPUT_LIMIT", "The bounded model input is full");
                    }
                    Dispatch dispatch = active.get();
                    dispatch.send();
                    try (ClientHttpResponse response = execution.execute(request, body)) {
                        if (response.getStatusCode().value() != 200) {
                            throw new QaException(
                                    "UPSTREAM_UNCERTAIN", "The upstream call did not return a usable completion");
                        }
                        byte[] bytes = response.getBody().readNBytes(131_073);
                        if (bytes.length > 131_072) {
                            throw new QaException("UPSTREAM_UNCERTAIN", "The model response exceeds 128 KiB");
                        }
                        inspect.accept(bytes);
                        return new Buffered(response.getHeaders(), bytes);
                    }
                });
    }

    RestClient.Builder rest() {
        return rest.clone();
    }

    <T> T call(Duration remaining, Runnable beforeDispatch, Supplier<T> action) {
        if (remaining.isNegative() || remaining.isZero()) {
            throw new QaException("QA_EXPIRED", "Question time limit reached");
        }
        Duration timeout = remaining.compareTo(Duration.ofSeconds(45)) > 0 ? Duration.ofSeconds(45) : remaining;
        active.set(new Dispatch(timeout, beforeDispatch));
        try {
            return action.get();
        } finally {
            active.remove();
        }
    }

    private static final class Dispatch {
        private final Duration timeout;
        private final Runnable before;
        private boolean sent;

        private Dispatch(Duration timeout, Runnable before) {
            this.timeout = timeout;
            this.before = before;
        }

        private void send() {
            if (sent) {
                throw new QaException("UPSTREAM_UNCERTAIN", "A paid request cannot be replayed automatically");
            }
            before.run();
            sent = true;
        }
    }

    private record Buffered(HttpHeaders headers, byte[] bytes) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(200);
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {}
    }
}
