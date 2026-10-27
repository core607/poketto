package io.github.core607.poketto.executor.internal;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The worker's side of one HTTP/1.1 exchange, for fake workers: a request with a declared body, then a closing reply. */
final class WorkerHttp {
    private WorkerHttp() {}

    record Request(String method, String path, byte[] body) {
        boolean hello() {
            return method.equals("GET") && path.equals("/v1/hello");
        }
    }

    /** The request, or {@code null} when the client closed the connection without writing one. */
    static Request read(InputStream input) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = input.read();
            if (next < 0) {
                if (head.size() == 0) {
                    return null;
                }
                throw new EOFException("truncated worker request head");
            }
            head.write(next);
            if (head.size() > 8196) {
                throw new IOException("oversized worker request head");
            }
            matched = next == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : next == '\r' ? 1 : 0;
        }
        String[] lines = head.toString(US_ASCII).split("\r\n");
        String[] start = lines[0].split(" ");
        if (start.length != 3 || !start[2].equals("HTTP/1.1")) {
            throw new IOException("invalid worker request line");
        }
        int length = 0;
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0 && lines[index].substring(0, colon).equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(lines[index].substring(colon + 1).strip());
            }
        }
        byte[] body = input.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("truncated worker request body");
        }
        return new Request(start[0], start[1], body);
    }

    static void write(OutputStream output, byte[] body) throws IOException {
        write(output, body.length, body);
    }

    /** A reply that announces {@code declared} body bytes whatever it sends. */
    static void write(OutputStream output, int declared, byte[] body) throws IOException {
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + declared
                        + "\r\nConnection: close\r\n\r\n")
                .getBytes(US_ASCII));
        output.write(body);
        output.flush();
    }
}
