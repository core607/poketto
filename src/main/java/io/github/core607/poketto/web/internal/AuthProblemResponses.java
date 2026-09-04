package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@org.springframework.core.annotation.Order(-10)
class AuthProblemResponses {
    @ExceptionHandler(AuthException.class)
    ProblemDetail auth(AuthException exception) {
        HttpStatus status =
                switch (exception.code()) {
                    case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
                    case DENIED -> HttpStatus.FORBIDDEN;
                    case LAST_OWNER, ALREADY_INITIALIZED -> HttpStatus.CONFLICT;
                    case INVALID_INPUT, INVALID_INVITATION -> HttpStatus.BAD_REQUEST;
                };
        return ProblemDetail.forStatusAndDetail(status, "Authentication operation rejected: " + exception.code());
    }
}
