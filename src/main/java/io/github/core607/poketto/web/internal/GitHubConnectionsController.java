package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubConnections;
import io.github.core607.poketto.content.GitHubRepositoryReconnections;
import io.github.core607.poketto.spaces.GitHubSpaceCreation;
import io.github.core607.poketto.spaces.GitHubSpaceReconnection;
import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/workspaces/github")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class GitHubConnectionsController {
    private static final String FLOW = GitHubConnectionsController.class.getName() + ".flow";
    private static final String ATTEMPT = GitHubConnectionsController.class.getName() + ".attempt";
    private final GitHubConnections connections;
    private final GitHubSpaceCreation creations;
    private final GitHubSpaceReconnection reconnections;

    GitHubConnectionsController(
            GitHubConnections connections, GitHubSpaceCreation creations, GitHubSpaceReconnection reconnections) {
        this.connections = connections;
        this.creations = creations;
        this.reconnections = reconnections;
    }

    @GetMapping("/repositories/{workspaceId}")
    GitHubRepositoryReconnections.Status repositoryStatus(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable String workspaceId) {
        return reconnections.status(actor, WorkspaceId.parse(workspaceId));
    }

    @PostMapping("/repositories/{workspaceId}/reconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reconnect(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable String workspaceId,
            @RequestBody Reconnection body) {
        reconnections.reconnect(actor, WorkspaceId.parse(workspaceId), body.repositoryName());
    }

    record Reconnection(String repositoryName) {
        Reconnection {
            if (!validName(repositoryName)) {
                throw new IllegalArgumentException("GitHub repository name is invalid");
            }
        }

        private static boolean validName(String name) {
            return name != null && name.matches("[A-Za-z0-9_.-]{1,100}") && !name.equals(".") && !name.equals("..");
        }
    }

    @GetMapping
    GitHubConnections.Status status(@AuthenticationPrincipal AuthPrincipal actor) {
        return connections.status(actor);
    }

    @PostMapping("/installation")
    Destination installation(@AuthenticationPrincipal AuthPrincipal actor) {
        return new Destination(connections.installationUrl(actor));
    }

    @PostMapping("/creations")
    GitHubSpaceCreation.Result create(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestBody GitHubSpaceCreation.Request body) {
        return creations.create(actor, body);
    }

    @GetMapping("/creations")
    GitHubSpaceCreation.History history(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam(defaultValue = "0") int offset) {
        return creations.history(actor, offset);
    }

    @GetMapping("/creations/{requestId}")
    GitHubSpaceCreation.Result creationStatus(
            @AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID requestId) {
        return creations.status(actor, requestId);
    }

    @PostMapping("/creations/{requestId}/resume")
    GitHubSpaceCreation.Result resume(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable UUID requestId) {
        return creations.resume(actor, requestId);
    }

    @PostMapping("/start")
    Destination start(@AuthenticationPrincipal AuthPrincipal actor, HttpServletRequest request) {
        GitHubConnections.Authorization authorization = connections.begin(actor);
        HttpSession session = request.getSession(true);
        synchronized (session) {
            session.setAttribute(FLOW, authorization);
            session.setAttribute(ATTEMPT, authorization.state());
        }
        return new Destination(authorization.url());
    }

    @GetMapping("/callback")
    void callback(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response) {
        GitHubConnections.Authorization authorization = consume(request, state);
        if (authorization == null) {
            redirect(response, "failed");
            return;
        }
        try {
            if (error != null) {
                redirect(response, "cancelled");
                return;
            }
            connections.complete(actor, authorization, code, () -> requireSession(request, authorization));
            redirect(response, "connected");
        } catch (AuthException | GitHubConnectionException failure) {
            redirect(response, "failed");
        } finally {
            clearAttempt(request, authorization.state());
        }
    }

    @DeleteMapping
    GitHubConnections.Status disconnect(
            @AuthenticationPrincipal AuthPrincipal actor, @RequestParam long version, HttpServletRequest request) {
        // Cancel browser consent before waiting for the account lock, so an initial callback
        // cannot create its first grant after a successful version-zero disconnect.
        HttpSession session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                session.removeAttribute(FLOW);
                session.removeAttribute(ATTEMPT);
            }
        }
        return connections.disconnect(actor, version);
    }

    private static GitHubConnections.Authorization consume(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null || state == null || state.length() != 43) {
            return null;
        }
        synchronized (session) {
            Object value = session.getAttribute(FLOW);
            if (!(value instanceof GitHubConnections.Authorization authorization)) {
                return null;
            }
            if (!MessageDigest.isEqual(
                    state.getBytes(StandardCharsets.UTF_8),
                    authorization.state().getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            session.removeAttribute(FLOW);
            return authorization;
        }
    }

    private static void requireSession(HttpServletRequest request, GitHubConnections.Authorization authorization) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
        }
        try {
            Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            var authentication = value instanceof SecurityContext context ? context.getAuthentication() : null;
            Object principal = authentication == null ? null : authentication.getPrincipal();
            if (!(principal instanceof AuthPrincipal actor)) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
            if (!actor.accountId().equals(authorization.accountId())
                    || actor.credentialVersion() != authorization.credentialVersion()) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
            if (!authorization.state().equals(session.getAttribute(ATTEMPT))) {
                throw new AuthException(AuthException.Code.INVALID_CREDENTIALS);
            }
        } catch (IllegalStateException invalidated) {
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, invalidated);
        }
    }

    private static void clearAttempt(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (state.equals(session.getAttribute(ATTEMPT))) {
                session.removeAttribute(ATTEMPT);
            }
        }
    }

    private static void redirect(HttpServletResponse response, String result) {
        response.setStatus(303);
        response.setHeader("Location", "/admin?tab=account&github=" + result);
    }

    @ExceptionHandler(GitHubConnectionException.class)
    ProblemDetail failure(GitHubConnectionException failure) {
        HttpStatus status =
                switch (failure.code()) {
                    case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
                    case AUTHORIZATION_CHANGED, IDENTITY_CHANGED, REPOSITORY_CHANGED -> HttpStatus.CONFLICT;
                    case AUTHORIZATION_REQUIRED, INSTALLATION_REQUIRED -> HttpStatus.FORBIDDEN;
                    default -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        var problem = ProblemDetail.forStatusAndDetail(status, "GitHub connection could not be completed");
        problem.setProperty("code", failure.code().name());
        return problem;
    }

    record Destination(String url) {
        @Override
        public String toString() {
            return "GitHubDestination[redacted]";
        }
    }
}
