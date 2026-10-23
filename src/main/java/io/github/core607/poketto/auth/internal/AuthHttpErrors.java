package io.github.core607.poketto.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class AuthHttpErrors {
    private AuthHttpErrors() {}

    static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return servletPath.isEmpty()
                ? request.getRequestURI().substring(request.getContextPath().length())
                : servletPath;
    }

    static void write(HttpServletResponse response, int status) throws IOException {
        write(response, status, null);
    }

    /** The code is a fixed enum name, never caller input, so it needs no escaping. */
    static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"Request rejected\",\"status\":" + status
                        + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}");
    }
}
