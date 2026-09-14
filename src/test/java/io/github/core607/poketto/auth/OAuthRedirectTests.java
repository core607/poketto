package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A callback a client cannot receive on excludes that client entirely, and one this server wrongly
 * believes is local hands an authorization code to whoever controls the name. These fix both
 * edges: which loopback forms a native client may register, and what stays refused.
 *
 * <p>Refusals are checked by the error code the client receives rather than by exception type,
 * because that code is the contract.
 */
class OAuthRedirectTests {

    private static final String REFUSED = "invalid_redirect_uri";

    @Test
    void aBrowserClientKeepsItsSecureCallback() {
        assertThatCode(() -> OAuthRedirects.validate("https://chat.example.com/oauth/callback"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNativeClientMayRegisterEachLoopbackForm() {
        assertThatCode(() -> OAuthRedirects.validate("http://localhost:52765/callback"))
                .doesNotThrowAnyException();
        assertThatCode(() -> OAuthRedirects.validate("http://127.0.0.1:52765/callback"))
                .doesNotThrowAnyException();
        assertThatCode(() -> OAuthRedirects.validate("http://[::1]:52765/callback"))
                .doesNotThrowAnyException();
    }

    @Test
    void anyPortIsPermittedBecauseTheClientCannotReserveOne() {
        assertThatCode(() -> OAuthRedirects.validate("http://127.0.0.1:1024/cb"))
                .doesNotThrowAnyException();
        assertThatCode(() -> OAuthRedirects.validate("http://127.0.0.1:65535/cb"))
                .doesNotThrowAnyException();
        assertThatCode(() -> OAuthRedirects.validate("http://127.0.0.1/cb")).doesNotThrowAnyException();
    }

    @Test
    void aPlainCallbackOnAnyOtherHostStaysRefused() {
        assertThatThrownBy(() -> OAuthRedirects.validate("http://chat.example.com/callback"))
                .hasMessage(REFUSED);
    }

    /** A name resolving to a loopback address is resolved on the client, not here. */
    @Test
    void aHostThatMerelyLooksLocalStaysRefused() {
        assertThatThrownBy(() -> OAuthRedirects.validate("http://localhost.evil.example/callback"))
                .hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("http://127.0.0.1.evil.example/callback"))
                .hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("http://notlocalhost/callback"))
                .hasMessage(REFUSED);
    }

    @Test
    void theRemainingChecksApplyToALoopbackCallbackToo() {
        assertThatThrownBy(() -> OAuthRedirects.validate("http://user:secret@127.0.0.1:8080/cb"))
                .hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("http://127.0.0.1:8080/cb#fragment"))
                .hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("http://127.0.0.1:8080/cb\\x"))
                .hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("http://127.0.0.1:8080/" + "x".repeat(2048)))
                .hasMessage(REFUSED);
    }

    @Test
    void anUnusableSchemeStaysRefused() {
        assertThatThrownBy(() -> OAuthRedirects.validate("ftp://127.0.0.1/cb")).hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("javascript:alert(1)")).hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate("myapp://127.0.0.1/cb"))
                .hasMessage(REFUSED);
        assertThatThrownBy(() -> OAuthRedirects.validate(null)).hasMessage(REFUSED);
    }
}
