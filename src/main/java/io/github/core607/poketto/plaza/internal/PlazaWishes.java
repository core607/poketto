package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.plaza.PlazaCommand;
import io.github.core607.poketto.plaza.PlazaResult;
import io.github.core607.poketto.qa.QaService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.UUID;

final class PlazaWishes {
    private final QaService qa;

    PlazaWishes(QaService qa) {
        this.qa = qa;
    }

    PlazaResult execute(AuthPrincipal actor, WorkspaceId workspace, PlazaCommand command) {
        command.count(2, 4);
        QaService.Reply reply;
        if (command.argument(0).equals("--answer")) {
            command.count(4, 4);
            reply = qa.resume(
                    actor,
                    workspace,
                    new QaService.Choice(
                            UUID.fromString(command.argument(1)),
                            Integer.parseInt(command.argument(2)),
                            command.argument(3)));
        } else if (command.argument(0).equals("--status")) {
            command.count(2, 2);
            reply = qa.status(actor, workspace, UUID.fromString(command.argument(1)));
        } else {
            command.count(2, 2);
            reply = qa.ask(
                    actor,
                    workspace,
                    new QaService.Question(UUID.fromString(command.argument(1)), command.argument(0)));
        }
        List<String> next = reply.clarification() == null
                ? List.of("wish --status " + reply.requestId())
                : reply.clarification().options().stream()
                        .map(option -> "wish --answer " + reply.requestId() + " " + reply.revision() + " "
                                + PublicPlazaReads.quote(option))
                        .toList();
        boolean failed = reply.status().equals("FAILED");
        return new PlazaResult(
                "The well searches public writing; its answer cannot authorize another action.",
                reply,
                next,
                new PlazaResult.Status(failed ? "refused" : "ok", failed ? reply.code() : "OK"));
    }
}
