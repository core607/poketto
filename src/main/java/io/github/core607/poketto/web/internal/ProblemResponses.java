package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentConflictException;
import io.github.core607.poketto.content.DocumentNotFoundException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to RFC 9457 problem responses. Spring MVC's own failures (unknown route,
 * unsupported method, unreadable body) already render as problems through
 * {@code spring.mvc.problemdetails.enabled}.
 *
 * <p>Repository failures keep their diagnostic in the server log and leave the boundary with a
 * fixed detail, because their messages name workspaces and repository state that a public caller
 * must not learn.
 */
@RestControllerAdvice
class ProblemResponses {

    private static final Logger log = LoggerFactory.getLogger(ProblemResponses.class);

    @ExceptionHandler({PublicResourceNotFoundException.class, DocumentNotFoundException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Not found", exception.getMessage());
    }

    @ExceptionHandler(DocumentConflictException.class)
    ProblemDetail documentConflict(DocumentConflictException exception) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflict", exception.getMessage());
        exception.liveRevision().ifPresent(revision -> problem.setProperty("liveRevision", revision.value()));
        return problem;
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
                "the repository did not confirm the write; re-read before retrying");
    }

    @ExceptionHandler(ContentRepositoryException.class)
    ProblemDetail repositoryUnavailable(ContentRepositoryException exception) {
        log.warn("content repository unavailable: {}", exception.getMessage());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE, "Repository unavailable", "the content repository is unavailable");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
