package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CreatorApplications;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.community.Community;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Site-wide moderation reads: counts and the space list. Neither grants workspace authority. */
@RestController
@RequestMapping("/api/auth/site")
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class SiteOverviewController {
    static final Duration RECENT = Duration.ofDays(7);

    private final SitePolicyService policies;
    private final Community community;
    private final CreatorApplications applications;
    private final Clock clock;

    SiteOverviewController(SitePolicyService policies, Community community, CreatorApplications applications) {
        this.policies = policies;
        this.community = community;
        this.applications = applications;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/overview")
    Overview overview(@AuthenticationPrincipal AuthPrincipal actor) {
        SitePolicyService.Overview counts =
                policies.overview(actor, clock.instant().minus(RECENT));
        return new Overview(
                counts.accounts(),
                counts.newAccounts(),
                counts.suspendedAccounts(),
                counts.spaces(),
                counts.publicSpaces(),
                counts.restrictedSpaces(),
                community.openReports(actor),
                applications.open(actor));
    }

    @GetMapping("/workspaces")
    AuthService.Page<SitePolicyService.SiteSpace> workspaces(
            @AuthenticationPrincipal AuthPrincipal actor,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "ALL") SitePolicyService.SpaceState state,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return policies.spaces(actor, query, state, offset, limit);
    }

    record Overview(
            Map<SiteGroup, Long> accounts,
            long newAccounts,
            long suspendedAccounts,
            long spaces,
            long publicSpaces,
            long restrictedSpaces,
            long openReports,
            long openApplications) {}
}
