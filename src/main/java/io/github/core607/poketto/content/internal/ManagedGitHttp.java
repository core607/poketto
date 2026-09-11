package io.github.core607.poketto.content.internal;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.JDKHttpConnection;

/** User-connected remotes can contact only their fixed provider's smart Git endpoints. */
final class ManagedGitHttp implements HttpConnectionFactory {
    private final URI repository;

    ManagedGitHttp(String repository) {
        this.repository = URI.create(repository);
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        return create(url, Proxy.NO_PROXY);
    }

    @Override
    public HttpConnection create(URL url, Proxy ignored) throws IOException {
        validate(repository, url);
        PublicNetworkDestination.requirePublic(url.getHost());
        return new Connection(url);
    }

    static void validate(URI repository, URL target) throws IOException {
        String path = target.getPath();
        String suffix = path.startsWith(repository.getPath() + "/")
                ? path.substring(repository.getPath().length())
                : "";
        if (!"https".equals(target.getProtocol())
                || !repository.getHost().equals(target.getHost())
                || (target.getPort() != -1 && target.getPort() != 443)
                || target.getUserInfo() != null
                || target.getRef() != null
                || !(suffix.equals("/info/refs")
                        || suffix.equals("/git-upload-pack")
                        || suffix.equals("/git-receive-pack"))
                || (target.getQuery() != null
                        && !(suffix.equals("/info/refs")
                                && (target.getQuery().equals("service=git-upload-pack")
                                        || target.getQuery().equals("service=git-receive-pack"))))) {
            throw new IOException("Repository request target is not allowed");
        }
    }

    private static final class Connection extends JDKHttpConnection {
        Connection(URL url) throws IOException {
            super(url, Proxy.NO_PROXY);
            super.setInstanceFollowRedirects(false);
        }

        @Override
        public void setInstanceFollowRedirects(boolean ignored) {
            super.setInstanceFollowRedirects(false);
        }
    }
}
