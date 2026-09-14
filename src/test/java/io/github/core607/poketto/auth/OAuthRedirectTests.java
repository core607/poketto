package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
 *
 * <p>Registration and the authorization request are checked separately: accepting a callback at
 * registration means nothing if the request naming it is then turned away.
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

    @Test
    void aLoopbackClientMayListenOnAPortItLearnsAfterRegistering() {
        assertThat(OAuthRedirects.permits("http://127.0.0.1/cb", "http://127.0.0.1:52765/cb"))
                .isTrue();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1234/cb", "http://127.0.0.1:52765/cb"))
                .isTrue();
        assertThat(OAuthRedirects.permits("http://localhost:1234/callback", "http://localhost:9/callback"))
                .isTrue();
    }

    @Test
    void anUnchangedCallbackIsPermittedAnywhere() {
        assertThat(OAuthRedirects.permits("https://chat.example.com/cb", "https://chat.example.com/cb"))
                .isTrue();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:52765/cb", "http://127.0.0.1:52765/cb"))
                .isTrue();
    }

    @Test
    void onlyThePortMayDifferAndOnlyOnLoopback() {
        assertThat(OAuthRedirects.permits("https://chat.example.com/cb", "https://chat.example.com:8443/cb"))
                .isFalse();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb", "http://127.0.0.1:2/other"))
                .isFalse();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb?a=1", "http://127.0.0.1:2/cb?a=2"))
                .isFalse();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb", "http://localhost:1/cb"))
                .isFalse();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb", "https://127.0.0.1:1/cb"))
                .isFalse();
    }

    /** A different port must not be a way past the checks a registered address had to pass. */
    @Test
    void aRequestedCallbackIsCheckedLikeARegisteredOne() {
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb", "http://user:secret@127.0.0.1:2/cb"))
                .isFalse();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb", "http://127.0.0.1:2/cb#fragment"))
                .isFalse();
        assertThat(OAuthRedirects.permits("http://127.0.0.1:1/cb", null)).isFalse();
        assertThat(OAuthRedirects.permits(null, "http://127.0.0.1:2/cb")).isFalse();
    }
}
