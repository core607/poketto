package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import io.github.core607.poketto.qa.QaSources;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class DefaultQaService implements QaService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultQaService.class);
    private final QaAuthority authority;
    private final QaLedger ledger;
    private final QaModels models;
    private final QaSources sources;
    private final QaPolicy policy;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean configured;
    private final ConcurrentHashMap<QaLedger.Key, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("qa-expiry").factory());
    private volatile boolean closed;

    DefaultQaService(
            QaAuthority authority,
            QaLedger ledger,
            QaModels models,
            QaSources sources,
            QaPolicy policy,
            ObjectMapper json,
            Clock clock,
            boolean configured) {
        this.authority = authority;
        this.ledger = ledger;
        this.models = models;
        this.sources = sources;
        this.policy = policy;
        this.json = json;
        this.clock = clock;
        this.configured = configured;
    }

    void start() {
        cleanup.scheduleWithFixedDelay(this::expireSafely, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public boolean available() {
        return configured && models.available() && !closed;
    }

    @Override
    public Reply ask(AuthPrincipal actor, WorkspaceId connection, Question input) {
        requireAvailable();
        QaLedger.Start started = authority.with(
                actor,
                connection,
                account -> ledger.begin(account, input.requestId(), credential(actor, connection), input.provider()));
        if (!started.created()) {
            return state(started.run());
        }
        var key = new QaLedger.Key(started.run().account(), input.requestId());
        var session = new Session(
                new QaConversation(sources, json, input.question(), policy.personality()),
                started.run().expires());
        sessions.put(key, session);
        session.lock.lock();
        try {
            return run(actor, connection, started.run(), session);
        } finally {
            session.lock.unlock();
        }
    }

    @Override
    public Reply resume(AuthPrincipal actor, WorkspaceId connection, Choice input) {
        requireAvailable();
        QaLedger.Run before = current(actor, connection, input.requestId());
        var key = new QaLedger.Key(before.account(), before.request());
        Session session = sessions.get(key);
        if (session == null) {
            return lost(before);
        }
        if (!session.lock.tryLock()) {
            throw new QaException("QA_BUSY", "This question already has an active request");
        }
        try {
            QaLedger.Run resumed = authority.with(
                    actor,
                    connection,
                    account -> ledger.resume(
                            ledger.require(account, input.requestId(), credential(actor, connection)),
                            input.revision()));
            session.conversation.resume(input.answer());
            session.expires = resumed.expires();
            return run(actor, connection, resumed, session);
        } finally {
            session.lock.unlock();
        }
    }

    @Override
    public Reply status(AuthPrincipal actor, WorkspaceId connection, UUID requestId) {
        QaLedger.Run current = current(actor, connection, requestId);
        if (current.status().equals("WAITING")
                && !sessions.containsKey(new QaLedger.Key(current.account(), requestId))) {
            return lost(current);
        }
        return state(current);
    }

    @Override
    public Allowance allowance(AuthPrincipal actor) {
        requireAvailable();
        return authority.with(actor, null, ledger::allowance);
    }

    private Reply run(AuthPrincipal actor, WorkspaceId connection, QaLedger.Run initial, Session session) {
        boolean waitingForUser = false;
        try {
            while (true) {
                requireAvailable();
                QaLedger.Run current = current(actor, connection, initial.request());
                QaLedger.requireRunning(current);
                QaModel.Completion completion = modelTurn(actor, connection, initial, current, session);
                QaConversation.Outcome outcome = session.conversation.accept(
                        completion, () -> QaLedger.requireRunning(current(actor, connection, initial.request())));
                if (outcome instanceof QaConversation.Waiting) {
                    QaLedger.Run waiting = authority.with(
                            actor,
                            connection,
                            account -> ledger.waiting(
                                    ledger.require(account, initial.request(), credential(actor, connection))));
                    session.expires = waiting.expires();
                    Reply reply = state(waiting);
                    waitingForUser = true;
                    return reply;
                }
                if (outcome instanceof QaConversation.Finished finished) {
                    return complete(
                            actor,
                            connection,
                            initial.request(),
                            finished,
                            session.conversation.activity().snapshot());
                }
            }
        } catch (QaException failure) {
            session.conversation.activity().fail(failure.code());
            if (failure.code().equals("OWNER_CONSENT_REQUIRED")) {
                sessions.remove(new QaLedger.Key(initial.account(), initial.request()), session);
            }
            return failed(initial, failure.code());
        } catch (AuthException revoked) {
            failed(initial, "AUTHORIZATION_CHANGED");
            throw revoked;
        } catch (RuntimeException failure) {
            log.warn("QA run failed outside the model protocol boundary", failure);
            session.conversation.activity().fail("QA_FAILED");
            return failed(initial, "QA_FAILED");
        } finally {
            if (!waitingForUser) {
                sessions.remove(new QaLedger.Key(initial.account(), initial.request()), session);
            }
        }
    }

    private QaModel.Completion modelTurn(
            AuthPrincipal actor, WorkspaceId connection, QaLedger.Run initial, QaLedger.Run current, Session session) {
        QaModel model = models.require(current.selection().provider()).client();
        model.validate(session.conversation.messages());
        int thinking = session.conversation
                .activity()
                .start("thinking", current.selection().model(), "");
        authority.with(
                actor,
                connection,
                account -> ledger.dispatch(ledger.require(account, initial.request(), credential(actor, connection))));
        QaModel.Completion completion =
                model.complete(session.conversation.messages(), Duration.between(clock.instant(), current.expires()));
        record(initial, () -> ledger.settle(ledger.find(initial.account(), initial.request()), completion));
        if (completion.refused()) {
            throw new QaException("MODEL_REFUSED", "The selected model declined to answer");
        }
        session.conversation.activity().finish(thinking, completion.reasoning(), "COMPLETED");
        return completion;
    }

    private Reply complete(
            AuthPrincipal actor,
            WorkspaceId connection,
            UUID request,
            QaConversation.Finished finished,
            List<Activity> activity) {
        QaLedger.Run completed = authority.with(actor, connection, account -> {
            QaLedger.Run active = ledger.require(account, request, credential(actor, connection));
            QaLedger.requireRunning(active);
            return ledger.finish(active, null);
        });
        return new Reply(
                completed.request(),
                "COMPLETED",
                "OK",
                completed.revision(),
                finished.paragraphs(),
                null,
                finished.notice(),
                completed.usage(),
                completed.selection(),
                activity);
    }

    private Reply failed(QaLedger.Run run, String code) {
        return state(record(run, () -> ledger.finish(ledger.find(run.account(), run.request()), code)));
    }

    private Reply lost(QaLedger.Run run) {
        return failed(run, "CONTINUATION_LOST");
    }

    private QaLedger.Run current(AuthPrincipal actor, WorkspaceId connection, UUID request) {
        return authority.with(
                actor, connection, account -> ledger.require(account, request, credential(actor, connection)));
    }

    private <T> T record(QaLedger.Run run, Supplier<T> operation) {
        return authority.record(run.account(), operation);
    }

    private Reply state(QaLedger.Run run) {
        var key = new QaLedger.Key(run.account(), run.request());
        Session session = sessions.get(key);
        if (!run.pending()) {
            sessions.remove(key);
        }
        Clarification clarification = null;
        QaConversation.Clarify value = session == null ? null : session.conversation.clarification();
        if (run.status().equals("WAITING") && value != null) {
            clarification = new Clarification(
                    value.question(), value.options(), run.expires().toString());
        }
        String notice =
                switch (run.status()) {
                    case "COMPLETED" -> "这次请求已完成，不会再次调用模型。完成后的问答内容不在服务器保留；若响应丢失，只能核对用量。";
                    case "WAITING" -> "请选择范围，也可以自行补充。等待期间不会调用模型。";
                    case "FAILED" ->
                        run.error().equals("MODEL_REFUSED")
                                ? "所选模型拒绝了这次请求。可以选择其他模型或重新编辑问题；已发生的调用仍计入用量和额度，预留糖果已退回。"
                                : "问答未完成（" + run.error() + "）。已退回本次预留的糖果；已发出的模型调用仍记入用量和额度。";
                    default -> "这次请求仍在处理。请查看状态，不要以新请求重复提交。";
                };
        String code = run.error() == null ? run.status() : run.error();
        return new Reply(
                run.request(),
                run.status(),
                code,
                run.revision(),
                List.of(),
                clarification,
                notice,
                run.usage(),
                run.selection(),
                session == null || run.status().equals("COMPLETED")
                        ? List.of()
                        : session.conversation.activity().snapshot());
    }

    private void expireSafely() {
        try {
            expire();
        } catch (RuntimeException failure) {
            log.warn("QA expiry could not settle pending run reservations", failure);
        }
    }

    void expire() {
        // Text expiry does not depend on the database being reachable for billing settlement.
        sessions.entrySet().removeIf(entry -> !entry.getValue().expires.isAfter(clock.instant()));
        for (QaLedger.Key key : ledger.pending()) {
            QaLedger.Run run =
                    authority.record(key.account(), () -> ledger.expire(ledger.find(key.account(), key.request())));
            if (!run.pending()) {
                sessions.remove(key);
            }
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new QaException("QA_UNAVAILABLE", "Creator QA is not configured or is disabled");
        }
    }

    private static UUID credential(AuthPrincipal actor, WorkspaceId connection) {
        return connection == null ? null : actor.subjectId();
    }

    @Override
    public void close() {
        closed = true;
        cleanup.shutdownNow();
        sessions.clear();
    }

    private static final class Session {
        private final ReentrantLock lock = new ReentrantLock();
        private final QaConversation conversation;
        private volatile Instant expires;

        private Session(QaConversation conversation, Instant expires) {
            this.conversation = conversation;
            this.expires = expires;
        }
    }
}
