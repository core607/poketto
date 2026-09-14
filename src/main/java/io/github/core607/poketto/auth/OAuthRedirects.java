package io.github.core607.poketto.auth;

import java.net.URI;

/**
 * Decides which callback address a client may register.
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
        if (value == null) {
            throw OAuthService.failure("invalid_redirect_uri");
        }
        try {
            URI uri = URI.create(value);
            if (value.length() > MAX_LENGTH
                    || !permittedScheme(uri)
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || value.indexOf('\\') >= 0
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw OAuthService.failure("invalid_redirect_uri");
            }
        } catch (IllegalArgumentException invalid) {
            throw OAuthService.failure("invalid_redirect_uri");
        }
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
