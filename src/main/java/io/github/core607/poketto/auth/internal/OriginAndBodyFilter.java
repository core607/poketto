package io.github.core607.poketto.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class OriginAndBodyFilter extends OncePerRequestFilter {
    static final int MAX_AUTH_BODY = 16 * 1024;
    private final Set<String> origins;

    OriginAndBodyFilter(Set<String> origins) {
        this.origins = Set.copyOf(origins);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var supplied = Collections.list(request.getHeaders("Origin"));
        if (!supplied.isEmpty() && (supplied.size() != 1 || !origins.contains(supplied.getFirst()))) {
            AuthHttpErrors.write(response, 403);
            return;
        }
        String path = request.getServletPath();
        if (path.isEmpty())
            path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/auth/") || path.startsWith("/api/admin/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            int limit = bodyLimit(path);
            if (request.getContentLengthLong() > limit) {
                AuthHttpErrors.write(response, 413);
                return;
            }
            if (request.getContentLengthLong() >= 0) {
                // The servlet container enforces HTTP framing for a declared body length.
                chain.doFilter(request, response);
                return;
            }
            String type = request.getContentType();
            if (path.equals("/api/admin/assets")
                    && type != null
                    && type.split(";", 2)[0].trim().equalsIgnoreCase("multipart/form-data")) {
                // Servlet getParts reads the original request and enforces the configured multipart limits.
                // Consuming that stream here would prevent the asset controller from receiving its file.
                chain.doFilter(request, response);
                return;
            }
            // Decide before MVC or form authentication can consume the body and handle read exceptions.
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) {
                AuthHttpErrors.write(response, 413);
                return;
            }
            try {
                chain.doFilter(new BoundedRequest(request, body), response);
            } catch (InvalidFormException exception) {
                AuthHttpErrors.write(response, 400);
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final ByteArrayInputStream body;
        private final Map<String, String[]> formParameters;
        private final Charset charset;
        private ServletInputStream stream;
        private BufferedReader reader;

        BoundedRequest(HttpServletRequest request, byte[] bytes) {
            super(request);
            body = new ByteArrayInputStream(bytes);
            try {
                charset = request.getCharacterEncoding() == null
                        ? StandardCharsets.UTF_8
                        : Charset.forName(request.getCharacterEncoding());
            } catch (IllegalArgumentException exception) {
                throw new InvalidFormException();
            }
            String type = request.getContentType();
            if (request.getMethod().equals("POST")
                    && bytes.length > 0
                    && type != null
                    && type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
                // Servlet parameter parsing reads the original stream, which has already been bounded.
                Map<String, List<String>> parameters = new LinkedHashMap<>();
                decodeForm(parameters, request.getQueryString(), StandardCharsets.UTF_8);
                decodeForm(parameters, new String(bytes, charset), charset);
                formParameters = new LinkedHashMap<>();
                parameters.forEach((name, values) -> formParameters.put(name, values.toArray(String[]::new)));
            } else {
                formParameters = null;
            }
        }

        @Override
        public ServletInputStream getInputStream() {
            if (reader != null) throw new IllegalStateException("getReader was already called");
            return input();
        }

        private ServletInputStream input() {
            if (stream == null)
                stream = new ServletInputStream() {
                    @Override
                    public int read() {
                        return body.read();
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) {
                        return body.read(buffer, offset, length);
                    }

                    @Override
                    public boolean isFinished() {
                        return body.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new IllegalStateException(
                                "Authentication and administration bodies use synchronous reads");
                    }
                };
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            if (reader == null) {
                if (stream != null) throw new IllegalStateException("getInputStream was already called");
                reader = new BufferedReader(new InputStreamReader(input(), charset));
            }
            return reader;
        }

        @Override
        public String getParameter(String name) {
            if (formParameters == null) return super.getParameter(name);
            String[] values = formParameters.get(name);
            return values == null ? null : values[0];
        }

        @Override
        public String[] getParameterValues(String name) {
            if (formParameters == null) return super.getParameterValues(name);
            String[] values = formParameters.get(name);
            return values == null ? null : values.clone();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return formParameters == null
                    ? super.getParameterNames()
                    : Collections.enumeration(formParameters.keySet());
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            if (formParameters == null) return super.getParameterMap();
            Map<String, String[]> copy = new LinkedHashMap<>();
            formParameters.forEach((key, values) -> copy.put(key, values.clone()));
            return Collections.unmodifiableMap(copy);
        }
    }

    private static void decodeForm(Map<String, List<String>> parameters, String form, Charset charset) {
        if (form == null || form.isEmpty()) return;
        try {
            for (String pair : form.split("&")) {
                if (pair.isEmpty()) continue;
                String[] entry = pair.split("=", 2);
                String name = URLDecoder.decode(entry[0], charset);
                String value = entry.length == 1 ? "" : URLDecoder.decode(entry[1], charset);
                parameters.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidFormException();
        }
    }

    private static final class InvalidFormException extends RuntimeException {}

    private static int bodyLimit(String path) {
        return switch (path) {
            case "/api/admin/repository/patch", "/api/admin/repository/preview" -> 6 * 1024 * 1024;
            case "/api/admin/assets" -> 17 * 1024 * 1024;
            default -> MAX_AUTH_BODY;
        };
    }
}
