package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.EmailChallengeException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-10)
class EmailProblemResponses {
    @ExceptionHandler(EmailChallengeException.class)
    ProblemDetail email(EmailChallengeException exception) {
        HttpStatus status =
                switch (exception.code()) {
                    case INVALID_INPUT, INVALID_CHALLENGE -> HttpStatus.BAD_REQUEST;
                    case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                    case DELIVERY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, "Email verification rejected: " + exception.code());
        problem.setProperty("code", exception.code().name());
        return problem;
    }
}
