package io.github.core607.poketto.auth;

import java.net.URI;
import java.util.Objects;

/**
 * Decides which callback address a client may register, and which address an authorization request
 * may then return to.
 *
 * <p>Requiring HTTPS everywhere serves a browser-hosted client and excludes every native one: a
 * command-line client has no domain and can obtain no certificate, so it listens on the loopback
 * interface and registers {@code http://127.0.0.1:<port>/…}. RFC 8252 names that redirection the
 * correct one for a native application and asks the authorization server to permit it on any port.
 * Loopback never leaves the machine, so plain HTTP there does not expose the authorization code to
 * the network the way it would on any other host.
 *
 * <p>Only the scheme requirement is relaxed, and only for loopback. Every other check applies
 * unchanged, and any other host still has to use HTTPS.
 */
public final class OAuthRedirects {

    private static final int MAX_LENGTH = 2048;

    private OAuthRedirects() {}

    public static void validate(String value) {
        if (!acceptable(value)) {
            throw OAuthService.failure("invalid_redirect_uri");
        }
    }

    /**
     * Whether an authorization request naming {@code requested} belongs to a client that registered
     * {@code registered}.
     *
     * <p>A native client cannot reserve a port: it asks the operating system for a free one when it
     * starts listening, which may be after it registered. RFC 8252 therefore asks the server to
     * ignore the port of a loopback callback when it matches the request against the registration.
     * Everything else — scheme, host, path and query — still has to be identical, the requested
     * address still has to pass every check a registered one does, and any other host is matched
     * literally, port included.
     *
     * <p>Ignoring the port lets one local program receive a code issued against another local
     * program's registration, and so under its displayed name. That needs code already running on
     * the user's machine, which could equally register a client of its own through open dynamic
     * registration, so it takes nothing away that exact matching was keeping.
     */
    public static boolean permits(String registered, String requested) {
        if (registered == null || requested == null) {
            return false;
        }
        if (registered.equals(requested)) {
            return true;
        }
        URI left = parse(registered);
        URI right = parse(requested);
        if (left == null || right == null || !acceptable(requested)) {
            return false;
        }
        return loopback(left.getHost())
                && loopback(right.getHost())
                && Objects.equals(left.getScheme(), right.getScheme())
                && Objects.equals(left.getHost(), right.getHost())
                && Objects.equals(path(left), path(right))
                && Objects.equals(left.getRawQuery(), right.getRawQuery());
    }

    private static boolean acceptable(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            return false;
        }
        URI uri = parse(value);
        return uri != null
                && permittedScheme(uri)
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && value.indexOf('\\') < 0
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static URI parse(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String path(URI uri) {
        String path = uri.getRawPath();
        return path == null ? "" : path;
    }

    private static boolean permittedScheme(URI uri) {
        if ("https".equals(uri.getScheme())) {
            return true;
        }
        return "http".equals(uri.getScheme()) && loopback(uri.getHost());
    }

    /**
     * The literal loopback names only. A name that merely resolves to a loopback address is not
     * one: resolution happens on the client's machine, so an attacker controlling a public name
     * that points at 127.0.0.1 would otherwise register a callback this server believes is local.
     */
    private static boolean loopback(String host) {
        if (host == null) {
            return false;
        }
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }
}
