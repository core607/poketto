package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.assets.ImageTransferException;
import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Raw, token-authorized image ingress. Browser sessions and MCP bearer credentials are not accepted here. */
@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class ImageTransferController {
    private final ImageTransfers transfers;
    private final ObjectMapper json;

    ImageTransferController(ImageTransfers transfers, ObjectMapper json) {
        this.transfers = transfers;
        this.json = json;
    }

    @PutMapping(path = ImageTransfers.UPLOAD_PATH + "{token}", consumes = "application/octet-stream")
    void upload(@PathVariable String token, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > ManagedBlobStore.MAX_UPLOAD_BYTES) {
            throw new AssetStorageException(AssetStorageException.Reason.TOO_LARGE);
        }
        var scope = transfers.reserve(token);
        ImageUploadBody collector = null;
        try {
            var context = request.startAsync();
            context.setTimeout(30_000);
            var input = request.getInputStream();
            collector = new ImageUploadBody(context, input, transfers, token, json, scope);
            context.addListener(collector);
            input.setReadListener(collector);
        } catch (IOException | IllegalStateException failure) {
            if (collector == null) {
                scope.responseComplete();
            } else {
                collector.onError(failure);
            }
            throw failure;
        }
    }

    @GetMapping(ImageTransfers.UPLOAD_PATH + "{token}")
    ImageTransfers.Receipt status(@PathVariable String token) {
        return transfers.status(token);
    }

    @ExceptionHandler(ImageTransferException.class)
    ResponseEntity<Failure> transferFailure(ImageTransferException failure) {
        return ResponseEntity.status(transferStatus(failure))
                .body(new Failure(failure.reason().name()));
    }

    static int transferStatus(ImageTransferException failure) {
        return switch (failure.reason()) {
            case TRANSFER_BUSY, IMAGE_MEMORY_BUSY -> 429;
            case UPLOAD_EXPIRED -> 410;
            case UPLOAD_PENDING -> 409;
            case SOURCE_UNAVAILABLE -> 502;
        };
    }

    @ExceptionHandler(AssetStorageException.class)
    ResponseEntity<Failure> storageFailure(AssetStorageException failure) {
        return ResponseEntity.status(storageStatus(failure))
                .body(new Failure(failure.reason().name()));
    }

    static int storageStatus(AssetStorageException failure) {
        return switch (failure.reason()) {
            case TOO_LARGE -> 413;
            case INVALID_IMAGE -> 422;
            case IDEMPOTENCY_CONFLICT -> 409;
            case NOT_FOUND -> 404;
            case UNAVAILABLE -> 503;
        };
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Failure> denied() {
        return ResponseEntity.status(403).body(new Failure("DENIED"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Failure> invalid() {
        return ResponseEntity.badRequest().body(new Failure("INVALID_INPUT"));
    }

    record Failure(String code) {}
}
