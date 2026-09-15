package io.github.core607.poketto.mcp.internal;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Bounds small MCP envelopes before SDK dispatch; image work owns its own memory reservations. */
final class McpBodyLimitFilter implements Filter {
    static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final Logger log = LoggerFactory.getLogger(McpBodyLimitFilter.class);
    private final ObjectMapper json;

    McpBodyLimitFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var http = (HttpServletRequest) request;
        var output = (HttpServletResponse) response;
        if (!http.getMethod().equals("POST")) {
            chain.doFilter(request, response);
            return;
        }
        if (http.getContentLengthLong() > MAX_REQUEST_BYTES) {
            reject(output, 413, "BODY_TOO_LARGE");
            return;
        }
        byte[] body = http.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            reject(output, 413, "BODY_TOO_LARGE");
            return;
        }
        McpEnvelopeBounds.Result envelope = new McpEnvelopeBounds(json).inspect(body, body.length);
        if (envelope == McpEnvelopeBounds.Result.TOO_COMPLEX) {
            reject(output, 413, "ENVELOPE_TOO_COMPLEX");
            return;
        }
        if (envelope != McpEnvelopeBounds.Result.VALID) {
            output.setStatus(400);
            output.setContentType("application/json");
            output.setHeader("Cache-Control", "no-store");
            String message = envelope == McpEnvelopeBounds.Result.INVALID_ID
                    ? "Invalid request identifier"
                    : "Invalid message format";
            output.getWriter()
                    .write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"" + message + "\"}}");
            log.warn("mcp envelope refused reason={}", envelope);
            return;
        }
        chain.doFilter(new BufferedRequest(http, body), response);
    }

    private void reject(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write(json.writeValueAsString(
                        new Refusal("about:blank", "MCP request rejected", status, "INVALID_INPUT", reason)));
        log.warn("mcp envelope refused code=INVALID_INPUT reason={}", reason);
    }

    private record Refusal(String type, String title, int status, String code, String reason) {}

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final ServletInputStream bounded;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            var source = new ByteArrayInputStream(body);
            bounded = new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return source.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("MCP request bodies use blocking reads");
                }
            };
        }

        @Override
        public ServletInputStream getInputStream() {
            return bounded;
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8));
        }
    }
}
