package io.github.core607.poketto.qa.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Limits bytes while receiving, including error bodies, before JSON parsing or buffering an entire response. */
final class BoundedModelBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if ((long) bytes.size() + buffer.remaining() > 131_072) {
                subscription.cancel();
                result.completeExceptionally(new IOException("QA upstream response exceeds 128 KiB"));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        result.complete(bytes.toByteArray());
    }
}
