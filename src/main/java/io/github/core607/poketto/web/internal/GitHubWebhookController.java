package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.GitHubWebhookException;
import io.github.core607.poketto.content.GitHubWebhooks;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class GitHubWebhookController {
    private final GitHubWebhooks webhooks;
    private final Semaphore admission = new Semaphore(1);

    GitHubWebhookController(GitHubWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(path = "/api/hooks/github", consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > GitHubWebhooks.MAX_BODY_BYTES) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.TOO_LARGE);
        }
        if (!admission.tryAcquire()) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.BUSY);
        }
        try {
            String signature = header(request, "X-Hub-Signature-256");
            if (signature == null || !signature.matches("sha256=[0-9a-f]{64}")) {
                throw new GitHubWebhookException(GitHubWebhookException.Code.INVALID_SIGNATURE);
            }
            String delivery = header(request, "X-GitHub-Delivery");
            String event = header(request, "X-GitHub-Event");
            byte[] body = request.getInputStream().readNBytes(GitHubWebhooks.MAX_BODY_BYTES + 1);
            if (body.length > GitHubWebhooks.MAX_BODY_BYTES) {
                throw new GitHubWebhookException(GitHubWebhookException.Code.TOO_LARGE);
            }
            webhooks.receive(delivery, event, signature, body);
        } finally {
            admission.release();
        }
    }

    private static String header(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1 || values.getFirst().length() > 256) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.MALFORMED);
        }
        return values.getFirst();
    }

    @ExceptionHandler(GitHubWebhookException.class)
    ProblemDetail webhookFailure(GitHubWebhookException failure) {
        HttpStatus status =
                switch (failure.code()) {
                    case INVALID_SIGNATURE -> HttpStatus.FORBIDDEN;
                    case MALFORMED -> HttpStatus.BAD_REQUEST;
                    case REPLAYED -> HttpStatus.CONFLICT;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
                    case TOO_LARGE -> HttpStatus.valueOf(413);
                };
        return ProblemDetail.forStatusAndDetail(status, "GitHub webhook could not be accepted");
    }
}
