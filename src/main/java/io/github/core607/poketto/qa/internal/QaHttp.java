package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One bounded dispatch, without redirects, retries or response-body logging. */
final class QaHttp {
    private QaHttp() {}

    static Duration timeout(Duration remaining) {
        if (remaining.isNegative() || remaining.isZero()) {
            throw new QaException("QA_EXPIRED", "Question time limit reached");
        }
        return remaining.compareTo(Duration.ofSeconds(45)) > 0 ? Duration.ofSeconds(45) : remaining;
    }

    static byte[] send(HttpClient http, HttpRequest request, Duration timeout) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new BoundedModelBody());
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new QaException("UPSTREAM_UNCERTAIN", "The upstream call did not return a usable completion");
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QaException(
                    "UPSTREAM_UNCERTAIN", "The upstream call was interrupted and will not be replayed", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new QaException(
                    "UPSTREAM_UNCERTAIN", "The upstream outcome is unknown and will not be replayed", failed);
        } finally {
            pending.cancel(true);
        }
    }
}
