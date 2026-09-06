package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class BrowserSecurityConfiguration {
    @Bean
    AuthenticationProvider accountAuthenticationProvider(ObjectProvider<AuthService> auth) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                if (auth.getIfAvailable() == null) throw new BadCredentialsException("Invalid credentials");
                try {
                    var principal = auth.getObject()
                            .authenticatePassword(
                                    authentication.getName(), String.valueOf(authentication.getCredentials()));
                    return new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT")));
                } catch (AuthException exception) {
                    throw new BadCredentialsException("Invalid credentials");
                }
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurity(
            HttpSecurity http,
            ObjectProvider<AuthService> auth,
            ObjectProvider<WorkspaceCatalog> workspaces,
            @Value("${poketto.security.allowed-origins:}") String origins)
            throws Exception {
        http.securityMatcher("/mcp", "/mcp/**")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> AuthHttpErrors.write(response, 401)))
                .addFilterBefore(new OriginAndBodyFilter(origins(origins)), AnonymousAuthenticationFilter.class)
                .addFilterBefore(
                        new WorkspaceIdentityFilter(auth, workspaces, true), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserSecurity(
            HttpSecurity http,
            AuthenticationProvider accountAuthenticationProvider,
            ObjectProvider<AuthService> auth,
            ObjectProvider<WorkspaceCatalog> workspaces,
            @Value("${poketto.security.allowed-origins:}") String origins,
            @Value("${poketto.security.admin-body-concurrency:2}") int adminBodyConcurrency,
            @Value("${poketto.security.login-limit-per-account:10}") int perAccount,
            @Value("${poketto.security.login-limit-per-address:40}") int perAddress,
            @Value("${poketto.security.login-throttle-max-entries:10000}") int maxEntries,
            @Value("${poketto.security.login-throttle-window-seconds:300}") long windowSeconds)
            throws Exception {
        http.authenticationProvider(accountAuthenticationProvider)
                .authorizeHttpRequests(requests -> requests.requestMatchers(
                                "/api/auth/csrf",
                                "/api/auth/login",
                                "/api/auth/initialize",
                                "/api/auth/invitations/register")
                        .permitAll()
                        .requestMatchers("/api/public/**")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .requestCache(cache -> cache.disable())
                .sessionManagement(sessions -> sessions.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(login -> login.loginPage("/login")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> AuthHttpErrors.write(response, 401)))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("POKETTO_SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, exception) -> AuthHttpErrors.write(response, 401))
                        .accessDeniedHandler((request, response, exception) -> AuthHttpErrors.write(response, 403)))
                .headers(headers -> headers.contentSecurityPolicy(
                        csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
                .addFilterBefore(new AdminBodyFilter(adminBodyConcurrency), CsrfFilter.class)
                .addFilterBefore(new WorkspaceIdentityFilter(auth, workspaces, false), AdminBodyFilter.class)
                .addFilterBefore(new OriginAndBodyFilter(origins(origins)), WorkspaceIdentityFilter.class)
                .addFilterBefore(
                        new LoginThrottleFilter(
                                Clock.systemUTC(),
                                perAccount,
                                perAddress,
                                maxEntries,
                                Duration.ofSeconds(windowSeconds)),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static Set<String> origins(String configured) {
        Set<String> values = Arrays.stream(configured.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
        for (String value : values) {
            URI uri = URI.create(value);
            if (!Set.of("https", "http").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "allowed origins must be exact HTTP(S) origins without paths or credentials");
            }
        }
        return values;
    }
}
