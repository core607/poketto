package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A request refused during authorization arrives with its identity already recognised, so these
 * fix that the identity outlives the security context rather than being read back from it.
 */
class RequestCallerTests {

    @Test
    void aRecognisedPrincipalOutlivesTheSecurityContext() {
        var request = new MockHttpServletRequest();
        UUID subject = UUID.randomUUID();

        RequestCaller.remember(request, new AuthPrincipal(AuthPrincipal.Kind.ACCOUNT, subject, subject, 0));

        assertThat(RequestCaller.of(request)).isEqualTo("ACCOUNT:" + subject);
    }

    @Test
    void anApiKeyIsNamedByItsKindAndSubject() {
        var request = new MockHttpServletRequest();
        UUID key = UUID.randomUUID();

        RequestCaller.remember(request, new AuthPrincipal(AuthPrincipal.Kind.API_KEY, key, UUID.randomUUID(), 0));

        assertThat(RequestCaller.of(request)).startsWith("API_KEY:").contains(key.toString());
    }

    @Test
    void aRequestThatReachedNoIdentityIsAnonymous() {
        assertThat(RequestCaller.of(new MockHttpServletRequest())).isEqualTo(RequestCaller.ANONYMOUS);
    }

    @Test
    void anAbsentPrincipalIsNotRemembered() {
        var request = new MockHttpServletRequest();

        RequestCaller.remember(request, null);

        assertThat(RequestCaller.of(request)).isEqualTo(RequestCaller.ANONYMOUS);
    }
}
