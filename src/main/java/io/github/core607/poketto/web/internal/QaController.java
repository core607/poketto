package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa")
class QaController {
    private final ObjectProvider<QaService> qa;

    QaController(ObjectProvider<QaService> qa) {
        this.qa = qa;
    }

    @GetMapping
    QaService.Allowance allowance(@AuthenticationPrincipal AuthPrincipal actor) {
        return service().allowance(actor);
    }

    @PostMapping
    QaService.Reply ask(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody QaService.Question input) {
        return service().ask(actor, null, input);
    }

    @PostMapping("/continue")
    QaService.Reply resume(@AuthenticationPrincipal AuthPrincipal actor, @RequestBody QaService.Choice input) {
        return service().resume(actor, null, input);
    }

    @GetMapping("/{id}")
    QaService.Reply status(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID id) {
        return service().status(actor, null, id);
    }

    @ExceptionHandler(QaException.class)
    ProblemDetail failure(QaException failure) {
        HttpStatus status =
                switch (failure.code()) {
                    case "QA_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
                    case "DAILY_LIMIT", "BUDGET_LIMIT", "QA_BUSY", "QA_CAPACITY" -> HttpStatus.TOO_MANY_REQUESTS;
                    case "QA_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "QA_CONFLICT", "QA_EXPIRED" -> HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The creator question could not be completed");
        problem.setProperty("code", failure.code());
        return problem;
    }

    private QaService service() {
        QaService value = qa.getIfAvailable();
        if (value == null) {
            throw new QaException("QA_UNAVAILABLE", "Creator QA is unavailable");
        }
        return value;
    }
}
