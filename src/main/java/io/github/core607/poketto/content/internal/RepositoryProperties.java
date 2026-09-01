package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import java.net.URI;
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("poketto.repository")
record RepositoryProperties(
        String remoteUri,
        String username,
        String password,
        Integer cacheMaxWorkspaces,
        Integer timeoutSeconds) {

    private static final int DEFAULT_CACHE_MAX_WORKSPACES = 32;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    RepositoryProperties {
        remoteUri = blankToNull(remoteUri);
        username = blankToNull(username);
        password = blankToNull(password);
        cacheMaxWorkspaces = cacheMaxWorkspaces == null
                ? DEFAULT_CACHE_MAX_WORKSPACES
                : cacheMaxWorkspaces;
        timeoutSeconds = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        if (cacheMaxWorkspaces < 1) {
            throw new IllegalArgumentException(
                    "poketto.repository.cache-max-workspaces must be positive");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "poketto.repository.timeout-seconds must be positive");
        }
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException(
                    "poketto.repository.username and poketto.repository.password must be configured together");
        }
    }

    RepositoryBinding requiredBinding() {
        if (remoteUri == null || username == null) {
            throw new ContentRepositoryException(
                    "remote repository authority requires poketto.repository.remote-uri, username, and password");
        }
        try {
            URI parsed = new URI(remoteUri);
            if (!"https".equalsIgnoreCase(parsed.getScheme())
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                throw invalidRemote();
            }
            return new RepositoryBinding(
                    new URIish(remoteUri),
                    new UsernamePasswordCredentialsProvider(username, password));
        } catch (URISyntaxException exception) {
            throw invalidRemote();
        }
    }

    @Override
    public String toString() {
        return "RepositoryProperties[remoteUri=redacted, username=redacted, password=redacted, "
                + "cacheMaxWorkspaces=" + cacheMaxWorkspaces
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }

    private static ContentRepositoryException invalidRemote() {
        return new ContentRepositoryException(
                "poketto.repository.remote-uri must be an HTTPS URI without embedded credentials, query, or fragment");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
