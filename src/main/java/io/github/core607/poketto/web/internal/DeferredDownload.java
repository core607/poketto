package io.github.core607.poketto.web.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A download body that commits nothing until its first byte arrives. Both download routes hand this
 * stream to a store that may still refuse -- authorization is checked again as the bytes are read,
 * and a stored original whose digest does not match is refused at that point. Once a status line and
 * headers are written the refusal can only reach the client as a truncated file, so the headers wait
 * until the store has actually produced something.
 */
final class DeferredDownload extends OutputStream {

    private final HttpServletResponse response;
    private final Runnable headers;
    private OutputStream stream;

    /** {@code headers} sets whatever this particular download needs, and runs at most once. */
    DeferredDownload(HttpServletResponse response, Runnable headers) {
        this.response = response;
        this.headers = headers;
    }

    @Override
    public void write(int value) throws IOException {
        stream().write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int count) throws IOException {
        stream().write(bytes, offset, count);
    }

    private OutputStream stream() throws IOException {
        if (stream == null) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            headers.run();
            stream = response.getOutputStream();
        }
        return stream;
    }
}
