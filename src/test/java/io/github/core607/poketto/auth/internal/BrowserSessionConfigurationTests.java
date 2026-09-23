package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.GoogleIdentityProvider;
import io.github.core607.poketto.auth.OAuthService;
import io.github.core607.poketto.content.GitHubConnections;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.util.LinkedMultiValueMap;

class BrowserSessionConfigurationTests {
    private final ConversionService conversion =
            BrowserSessionConfiguration.attributeConversion(getClass().getClassLoader());

    @Test
    void signedInContextAndCsrfTokenSurviveTheStore() throws Exception {
        AuthPrincipal principal = principal();
        var context = new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"))));
        var csrf = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token");

        var restored = (SecurityContextImpl) roundTrip(context);
        var restoredPrincipal = (AuthPrincipal) restored.getAuthentication().getPrincipal();
        assertThat(restoredPrincipal.accountId()).isEqualTo(principal.accountId());
        assertThat(restoredPrincipal.credentialVersion()).isEqualTo(principal.credentialVersion());
        assertThat(restored.getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ACCOUNT");
        assertThat(((DefaultCsrfToken) roundTrip(csrf)).getToken()).isEqualTo("token");
    }

    @Test
    void everyFlowHeldInASessionSurvivesTheStore() throws Exception {
        Instant expires = Instant.parse("2026-09-23T00:00:00Z");
        var client = new OAuthService.Client("client", "Claude", List.of("https://claude.example/cb"), expires);
        var pending = new HashMap<String, OAuthService.AuthorizationRequest>(Map.of(
                "request",
                new OAuthService.AuthorizationRequest(
                        client, "https://claude.example/cb", Set.of("content:read_private"), "s", "c", expires)));
        var github = new GitHubConnections.Authorization(
                "https://github.example", "state", "v", expires, UUID.randomUUID(), 3, 4);
        var google = new GoogleIdentityProvider.Authorization("https://google.example", "state", "nonce", "verifier");
        Class<?> pendingGoogle =
                Class.forName("io.github.core607.poketto.web.internal.GoogleIdentityController$Pending");
        Class<?> mode = Class.forName("io.github.core607.poketto.web.internal.GoogleIdentityController$Mode");
        Constructor<?> constructor = pendingGoogle.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object googleFlow =
                constructor.newInstance(google, mode.getEnumConstants()[1], principal(), "/community", expires);

        assertThat(roundTrip(pending)).isEqualTo(pending);
        assertThat(roundTrip(github)).isEqualTo(github);
        Object restored = roundTrip(googleFlow);
        assertThat(restored).isNotNull().hasSameClassAs(googleFlow);
        assertThat(restored.toString()).isEqualTo("GooglePending[REDACTED]");
    }

    @Test
    void classesOutsideTheAllowListAndDamagedBytesReadAsAbsent() {
        byte[] foreign = conversion.convert(new LinkedMultiValueMap<String, String>(), byte[].class);

        assertThat(conversion.convert(foreign, Object.class)).isNull();
        assertThat(conversion.convert(new byte[] {1, 2, 3}, Object.class)).isNull();
    }

    private Object roundTrip(Object value) {
        return conversion.convert(conversion.convert(value, byte[].class), Object.class);
    }

    private static AuthPrincipal principal() throws Exception {
        Constructor<AuthPrincipal> constructor = AuthPrincipal.class.getDeclaredConstructor(
                AuthPrincipal.Kind.class, UUID.class, UUID.class, long.class);
        constructor.setAccessible(true);
        UUID account = UUID.randomUUID();
        return constructor.newInstance(AuthPrincipal.Kind.ACCOUNT, account, account, 7L);
    }
}
