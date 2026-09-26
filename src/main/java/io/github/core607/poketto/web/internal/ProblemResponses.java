package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryMoveDependencyException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain and unexpected failures to RFC 9457 problem responses. Spring MVC's own failures
 * (unknown route, unsupported method, unreadable body) retain their specific problem mappings
 * through {@link ResponseEntityExceptionHandler}.
 *
 * <p>Repository failures keep their diagnostic in the server log and leave the boundary with a
 * fixed detail, because their messages name workspaces and repository state that a public caller
 * must not learn.
 */
@RestControllerAdvice
class ProblemResponses extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemResponses.class);

    @ExceptionHandler(RepositoryMoveDependencyException.class)
    ProblemDetail moveDependency(RepositoryMoveDependencyException exception) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Move dependency unavailable", exception.getMessage());
        problem.setProperty("code", "MOVE_UNPUBLISHABLE_DEPENDENCY");
        return problem;
    }

    @ExceptionHandler(ContentExportException.class)
    ProblemDetail exportFailure(ContentExportException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case CAPACITY -> HttpStatus.TOO_MANY_REQUESTS;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        return problem(status, "Export unavailable", exception.getMessage());
    }

    @ExceptionHandler(AssetStorageException.class)
    ProblemDetail assetFailure(AssetStorageException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
                    case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
                    case INVALID_IMAGE -> HttpStatus.BAD_REQUEST;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        return problem(status, "Image unavailable", exception.getMessage());
    }

    @ExceptionHandler(PublicResourceNotFoundException.class)
    ProblemDetail notFound(PublicResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Not found", exception.getMessage());
    }

    @ExceptionHandler(RepositoryConflictException.class)
    ProblemDetail repositoryConflict(RepositoryConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Conflict", "remote main changed while the write was being prepared");
    }

    @ExceptionHandler(RepositoryWriteAmbiguousException.class)
    ProblemDetail ambiguousWrite(RepositoryWriteAmbiguousException exception) {
        log.warn("repository write outcome unknown: {}", exception.getMessage());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Write outcome unknown",
                "write completion could not be confirmed; re-read remote main before retrying");
    }

    @ExceptionHandler(ContentRepositoryException.class)
    ProblemDetail repositoryUnavailable(ContentRepositoryException exception, HttpServletRequest request) {
        log.warn("content repository unavailable: {}", exception.getMessage());
        ContentRepositoryException.Recovery recovery =
                workspaceRecoveryAllowed(request) ? exception.recovery() : ContentRepositoryException.Recovery.NONE;
        String detail =
                switch (recovery) {
                    case NONE -> "the content repository is unavailable";
                    case RETRY -> "repository access is temporarily unavailable; re-read before retrying a write";
                    case RECONNECT ->
                        "the authorizing workspace owner must verify and restore the repository connection";
                };
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Repository unavailable", detail);
        if (recovery != ContentRepositoryException.Recovery.NONE) {
            problem.setProperty("code", "REPOSITORY_" + recovery.name());
        }
        return problem;
    }

    private static boolean workspaceRecoveryAllowed(HttpServletRequest request) {
        // The identity filter authorizes workspace membership before dispatch to these routes.
        // Public handlers retain generic failures even when the visitor has a browser session.
        Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(mapping instanceof String route) || !route.startsWith(WorkspaceHttpRoutes.ADMIN)) {
            return false;
        }
        if (!(request.getUserPrincipal() instanceof Authentication authentication)) {
            return false;
        }
        return authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthPrincipal actor
                && actor.kind() == AuthPrincipal.Kind.ACCOUNT;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpectedFailure(Exception exception) {
        log.error("unhandled request failure", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "an unexpected error occurred");
    }

    /**
     * Every mapped failure leaves a record naming what the caller was told. The caller receives a
     * fixed detail and no identifier, so this line and the request record it shares an identifier
     * with are the only account of why a request failed.
     */
    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        log.atWarn()
                .addKeyValue("status", status.value())
                .addKeyValue("title", title)
                .setMessage("request refused with {} {}")
                .addArgument(status.value())
                .addArgument(title)
                .log();
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(IllegalArgumentException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                "a request parameter is outside its allowed format or bounds");
    }
}
