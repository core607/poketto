package io.github.core607.poketto.qa.internal;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/** SDK transport seam: Spring's bounded HTTP exchange also prevents hidden redirects and retries. */
final class QaAnthropicTransport implements HttpClient {
    private final RestClient rest;
    private final AnthropicBackend backend;

    QaAnthropicTransport(QaTransport transport, AnthropicBackend backend) {
        this.rest = transport.rest().build();
        this.backend = backend;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        HttpRequest prepared = backend.authorizeRequest(backend.prepareRequest(request));
        var body = new ByteArrayOutputStream();
        if (prepared.body() != null) {
            prepared.body().writeTo(body);
        }
        return rest.method(HttpMethod.valueOf(prepared.method().name()))
                .uri(prepared.url())
                .headers(headers -> prepared.headers()
                        .names()
                        .forEach(name -> headers.put(name, prepared.headers().values(name))))
                .body(body.toByteArray())
                .exchange((outgoing, response) -> {
                    var headers = Headers.builder();
                    response.getHeaders().forEach(headers::put);
                    return new Receipt(
                            response.getStatusCode().value(),
                            headers.build(),
                            response.getBody().readAllBytes());
                });
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        throw new UnsupportedOperationException("QA uses one synchronous, ledger-controlled model call");
    }

    @Override
    public void close() {}

    private record Receipt(int statusCode, Headers headers, byte[] bytes) implements HttpResponse {
        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {}
    }
}
