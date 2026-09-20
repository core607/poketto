package io.github.core607.poketto.auth.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/** Bounds provider transport before Spring parses a success or error payload containing credentials. */
final class GoogleTokenClient {
    private GoogleTokenClient() {}

    static RestClientAuthorizationCodeTokenResponseClient create(Duration timeout) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(timeout);
        var rest = RestClient.builder()
                .requestFactory(requests)
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .requestInterceptor((request, body, execution) -> {
                    try (ClientHttpResponse response = execution.execute(request, body)) {
                        byte[] bytes = response.getBody().readNBytes(65_537);
                        if (bytes.length > 65_536) {
                            throw new IOException("Google token response exceeds the byte limit");
                        }
                        return new Response(
                                response.getStatusCode(), response.getStatusText(), response.getHeaders(), bytes);
                    }
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(rest);
        return client;
    }

    private record Response(HttpStatusCode status, String statusText, HttpHeaders headers, byte[] bytes)
            implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return statusText;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {}

        @Override
        public String toString() {
            return "GoogleTokenResponse[REDACTED]";
        }
    }
}
