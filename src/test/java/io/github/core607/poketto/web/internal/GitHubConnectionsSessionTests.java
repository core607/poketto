package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.GitHubConnections;
import io.github.core607.poketto.spaces.GitHubSpaceCreation;
import io.github.core607.poketto.spaces.GitHubSpaceReconnection;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.util.ConcurrentReferenceHashMap;

class GitHubConnectionsSessionTests {
    private static final String FLOW = GitHubConnectionsController.class.getName() + ".flow";
    private static final String ATTEMPT = GitHubConnectionsController.class.getName() + ".attempt";
    private static final String STATE = "s".repeat(43);

    @Test
    void aCallbackStopsWhenTheSavedSessionNoLongerHoldsItsAttempt() throws Exception {
        AuthPrincipal actor = principal();
        var authorization = new GitHubConnections.Authorization(
                "https://github.example", STATE, "verifier", Instant.now(), actor.accountId(), 0, 1);
        var repository = new MapSessionRepository(new ConcurrentReferenceHashMap<>());

        // This request's own copy still carries the attempt, as it was read before the disconnect.
        var copy = new MockHttpSession(null, "session-1");
        copy.setAttribute(FLOW, authorization);
        copy.setAttribute(ATTEMPT, STATE);
        var saved = new MapSession("session-1");
        saved.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context(actor));
        repository.save(saved);

        assertThat(callback(repository, copy, actor)).endsWith("github=failed");

        saved.setAttribute(ATTEMPT, STATE);
        repository.save(saved);
        copy.setAttribute(FLOW, authorization);
        assertThat(callback(repository, copy, actor)).endsWith("github=connected");
    }

    private static String callback(SessionRepository<?> repository, MockHttpSession session, AuthPrincipal actor) {
        GitHubConnections connections = mock(GitHubConnections.class);
        doAnswer(call -> {
                    call.<Runnable>getArgument(3).run();
                    return null;
                })
                .when(connections)
                .complete(any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionRepository<?>> provider = mock(ObjectProvider.class);
        doReturn(repository).when(provider).getIfAvailable();
        var controller = new GitHubConnectionsController(
                connections, mock(GitHubSpaceCreation.class), mock(GitHubSpaceReconnection.class), provider);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        controller.callback(actor, STATE, "code", null, request, response);
        return response.getHeader("Location");
    }

    private static SecurityContextImpl context(AuthPrincipal actor) {
        return new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"))));
    }

    private static AuthPrincipal principal() throws Exception {
        Constructor<AuthPrincipal> constructor = AuthPrincipal.class.getDeclaredConstructor(
                AuthPrincipal.Kind.class, UUID.class, UUID.class, long.class);
        constructor.setAccessible(true);
        UUID account = UUID.randomUUID();
        return constructor.newInstance(AuthPrincipal.Kind.ACCOUNT, account, account, 0L);
    }
}
