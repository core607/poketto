package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginThrottleTests {
    @Test
    void accountLimitsApplyAcrossAddressesAndIgnoreLoginCase() {
        var filter = new LoginThrottleFilter(Clock.systemUTC(), 2, 5, 100, Duration.ofMinutes(5));
        assertThat(filter.take("first-address", "Owner")).isTrue();
        assertThat(filter.take("second-address", "owner")).isTrue();
        assertThat(filter.take("third-address", "OWNER")).isFalse();
        assertThat(filter.take("third-address", "different")).isTrue();
    }

    @Test
    void addressAndEntryCapsFailClosedAndExpiredEntriesAreReclaimed() {
        var clock = new MutableClock();
        var filter = new LoginThrottleFilter(clock, 5, 2, 3, Duration.ofMinutes(5));
        assertThat(filter.take("first-address", "one")).isTrue();
        assertThat(filter.take("first-address", "two")).isTrue();
        assertThat(filter.take("first-address", "three")).isFalse();
        assertThat(filter.take("second-address", "four")).isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(filter.take("second-address", "four")).isTrue();
    }

    @Test
    void servletDecodedLoginPathCannotBypassThrottling() throws Exception {
        var filter = new LoginThrottleFilter(Clock.systemUTC(), 1, 5, 100, Duration.ofMinutes(5));
        var encoded = new MockHttpServletRequest("POST", "/%61pi/auth/login");
        encoded.setServletPath("/api/auth/login");
        encoded.setParameter("username", "owner");
        filter.doFilter(encoded, new MockHttpServletResponse(), new MockFilterChain());
        var canonical = new MockHttpServletRequest("POST", "/api/auth/login");
        canonical.setServletPath("/api/auth/login");
        canonical.setParameter("username", "owner");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(canonical, rejected, new MockFilterChain());
        assertThat(rejected.getStatus()).isEqualTo(429);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
