package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.assets.ImageTransferException;
import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthException;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import tools.jackson.databind.ObjectMapper;

/** Nonblocking, bounded upload collection. Timeout/disconnect releases memory even if the sender stalls. */
final class ImageUploadBody implements ReadListener, AsyncListener {
    private final AsyncContext context;
    private final ServletInputStream input;
    private final ImageTransfers transfers;
    private final String token;
    private final ObjectMapper json;
    private final ImageRequestScope scope;
    private final ImageRequestScope.Producer producer;
    private ByteArrayOutputStream body = new ByteArrayOutputStream();
    private boolean finished;

    ImageUploadBody(
            AsyncContext context,
            ServletInputStream input,
            ImageTransfers transfers,
            String token,
            ObjectMapper json,
            ImageRequestScope scope) {
        this.context = context;
        this.input = input;
        this.transfers = transfers;
        this.token = token;
        this.json = json;
        this.scope = scope;
        this.producer = scope.producer();
    }

    @Override
    public synchronized void onDataAvailable() throws IOException {
        byte[] buffer = new byte[8192];
        while (!finished && input.isReady() && !input.isFinished()) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            if (body.size() + count > ManagedBlobStore.MAX_UPLOAD_BYTES) {
                finish(413, new ImageTransferController.Failure("TOO_LARGE"));
                return;
            }
            body.write(buffer, 0, count);
        }
    }

    @Override
    public synchronized void onAllDataRead() {
        if (finished) {
            return;
        }
        try {
            byte[] bytes = body.toByteArray();
            body = null;
            var receipt = transfers.upload(token, bytes);
            finish(200, receipt);
        } catch (ImageTransferException failure) {
            finish(
                    ImageTransferController.transferStatus(failure),
                    new ImageTransferController.Failure(failure.reason().name()));
        } catch (AssetStorageException failure) {
            finish(
                    ImageTransferController.storageStatus(failure),
                    new ImageTransferController.Failure(failure.reason().name()));
        } catch (AuthException denied) {
            finish(403, new ImageTransferController.Failure("DENIED"));
        } catch (IllegalArgumentException invalid) {
            finish(400, new ImageTransferController.Failure("INVALID_INPUT"));
        }
    }

    private synchronized void finish(int status, Object result) {
        if (finished) {
            return;
        }
        finished = true;
        try {
            var response = (HttpServletResponse) context.getResponse();
            response.setStatus(status);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getOutputStream().write(json.writeValueAsBytes(result));
        } catch (IOException | IllegalStateException disconnected) {
            // The durable receipt, if any, remains queryable; do not retry storage after a lost response.
        } finally {
            body = null;
            producer.close();
            scope.responseComplete();
            try {
                context.complete();
            } catch (IllegalStateException completed) {
                // Container error completion can win this race.
            }
        }
    }

    @Override
    public void onError(Throwable failure) {
        finish(400, new ImageTransferController.Failure("UPLOAD_INTERRUPTED"));
    }

    @Override
    public void onTimeout(AsyncEvent event) {
        finish(408, new ImageTransferController.Failure("UPLOAD_TIMEOUT"));
    }

    @Override
    public void onError(AsyncEvent event) {
        onError(event.getThrowable());
    }

    @Override
    public void onComplete(AsyncEvent event) {
        finish(400, new ImageTransferController.Failure("UPLOAD_INTERRUPTED"));
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
        context.addListener(this);
    }
}
