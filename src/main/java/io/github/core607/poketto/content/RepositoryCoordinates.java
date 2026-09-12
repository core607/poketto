package io.github.core607.poketto.content;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Canonical provider coordinates for a user-connected repository, without credentials. */
public final class RepositoryCoordinates {
    private final String provider;
    private final String canonicalUri;

    private RepositoryCoordinates(String provider, String canonicalUri) {
        this.provider = provider;
        this.canonicalUri = canonicalUri;
    }

    public String provider() {
        return provider;
    }

    public String canonicalUri() {
        return canonicalUri;
    }

    public static RepositoryCoordinates parse(String input) {
        if (input == null || input.length() > 2048) {
            throw invalid();
        }
        try {
            URI uri = new URI(input.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw invalid();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("github.com") && !host.equals("cnb.cool")) {
                throw invalid();
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                throw invalid();
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty()) {
                throw invalid();
            }
            path = path.toLowerCase(Locale.ROOT);
            if (path.endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String[] segments = path.substring(1).split("/", -1);
            if (segments.length < 2 || segments.length > 20 || (host.equals("github.com") && segments.length != 2)) {
                throw invalid();
            }
            for (String segment : segments) {
                if (!segment.matches("[a-z0-9._-]{1,128}")
                        || segment.equals(".")
                        || segment.equals("..")
                        || segment.equals("-")) {
                    throw invalid();
                }
            }
            return new RepositoryCoordinates(host.equals("github.com") ? "github" : "cnb", "https://" + host + path);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    public String transportUri() {
        return canonicalUri + (provider.equals("github") ? ".git" : "");
    }

    @Override
    public String toString() {
        return "RepositoryCoordinates[provider=" + provider + "]";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Use an existing GitHub or CNB HTTPS repository URL without embedded credentials, query, or fragment");
    }
}
