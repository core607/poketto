package io.github.core607.poketto.web.internal;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-10)
@RestControllerAdvice(assignableTypes = {BrowserAuthController.class, WorkspaceAdminController.class})
class AuthInputProblemResponses {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid authentication request");
    }
}
