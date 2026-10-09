package io.github.core607.poketto.plaza.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.community.MachineCommunity;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.plaza.PlazaResult;
import io.github.core607.poketto.plaza.PlazaService;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import io.github.core607.poketto.workspace.internal.CommunityPublicationFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PlazaIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private AuthService auth;
    private MachineAccounts accounts;
    private SitePolicyService policy;
    private AuthPrincipal owner;
    private AuthPrincipal key;
    private WorkspaceId workspace;
    private WorkspacePublications publications;
    private final Snapshots snapshots = new Snapshots();
    private PlazaService plaza;
    private Clock plazaClock = Clock.systemUTC();

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(data).load().migrate();
        jdbc = new JdbcTemplate(data);
        jdbc.execute(
                "truncate table workspaces,auth_accounts,oauth_clients,machine_account_grants,plaza_notes,plaza_discoveries,plaza_wallets cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default,public_slug,public_delivery) "
                        + "values (?,'Plaza fixture',true,'street',true)",
                workspace.value());
        var manager = new DataSourceTransactionManager(data);
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, manager, passwords, event -> {}, Clock.systemUTC());
        owner = auth.initializeOwner("plaza-owner", "Plaza-fixture-password-2026!");
        var humanAccounts = new Accounts(jdbc, manager);
        accounts = new MachineAccounts(jdbc, humanAccounts, auth, manager);
        policy = new SitePolicyService(jdbc, humanAccounts, auth, manager);
        key = key(owner);
        publications = CommunityPublicationFixture.publications(jdbc);
        Instant now = Instant.now();
        var article = new PublicArticle(
                "public/source.md",
                "/paper",
                "Public paper",
                "Only the public needle.",
                List.of("quiet"),
                now,
                now,
                false,
                "Author",
                UUID.randomUUID(),
                false,
                null);
        snapshots.value = new PublicContentSnapshot(
                workspace, Optional.of("a".repeat(40)), now, now.plusSeconds(600), List.of(article));
        plaza = service();
    }

    private PlazaService service() {
        return new DefaultPlazaService(
                accounts,
                new PublicPlazaReads(publications, new WebsiteContentSnapshots(snapshots, publications)),
                new PlazaPocket(jdbc, plazaClock),
                new PlazaStreet(plazaClock),
                new PlazaWallet(jdbc, plazaClock),
                mock(MachineCommunity.class),
                true);
    }

    @Test
    void pocketRequiresHolderConsentAndSurvivesAReplacementConnectionAndService() {
        String request = UUID.randomUUID().toString();
        assertThat(run(key, "note \"hello future\" " + request).status().code()).isEqualTo("OWNER_CONSENT_REQUIRED");
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.POCKET));
        assertThat(run(key, "note \"hello future\" " + request).status().code()).isEqualTo("OK");
        AuthPrincipal replacement = key(owner);
        assertThat(run(replacement, "pocket").status().code()).isEqualTo("OWNER_CONSENT_REQUIRED");
        accounts.set(owner, replacement.subjectId(), Set.of(MachinePermission.POCKET));
        plaza = service();
        var notes = ((DefaultPlazaService.Pocket) run(replacement, "pocket").data()).notes();
        assertThat(notes).hasSize(1);
        assertThat(((PlazaPocket.Note) notes.getFirst()).body()).isEqualTo("hello future");
        assertThat(((PlazaPocket.Note) notes.getFirst()).clientName()).contains("self-reported");
    }

    @Test
    void keyManagerCannotGrantAnotherHoldersAccountAuthorityOrReadTheirNotes() {
        AuthPrincipal member = member();
        AuthPrincipal memberKey = key(member);
        assertThatThrownBy(() -> accounts.set(owner, memberKey.subjectId(), Set.of(MachinePermission.POCKET)))
                .isInstanceOf(AuthException.class);
        accounts.set(member, memberKey.subjectId(), Set.of(MachinePermission.POCKET));
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.POCKET));
        assertThat(run(memberKey, "note \"member secret\" " + UUID.randomUUID())
                        .status()
                        .code())
                .isEqualTo("OK");
        assertThat(((DefaultPlazaService.Pocket) run(key, "pocket").data()).notes())
                .isEmpty();
        assertThatThrownBy(() -> accounts.set(memberKey, memberKey.subjectId(), Set.of(MachinePermission.POCKET)))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void concurrentRetriesWriteOnceAndDeletionDoesNotAllowResurrection() throws Exception {
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.POCKET));
        String command = "note \"one paper\" " + UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> run(key, command));
            var second = pool.submit(() -> run(key, command));
            assertThat(first.get(10, TimeUnit.SECONDS).status().code()).isEqualTo("OK");
            assertThat(second.get(10, TimeUnit.SECONDS).status().code()).isEqualTo("OK");
        }
        var notes = ((DefaultPlazaService.Pocket) run(key, "pocket").data()).notes();
        assertThat(notes).hasSize(1);
        UUID id = ((PlazaPocket.Note) notes.getFirst()).id();
        assertThat(run(key, "note --remove " + id).status().code()).isEqualTo("OK");
        assertThat(run(key, command).status().code()).isEqualTo("NOTE_REMOVED");
        assertThat(jdbc.queryForObject("select body from plaza_notes where note_id=?", String.class, id))
                .isEmpty();
    }

    @Test
    void currentRoleRevocationAndPublicationAreCheckedOnEveryAction() {
        AuthPrincipal member = member();
        AuthPrincipal memberKey = key(member);
        assertThat(run(memberKey, "read street/paper").status().code()).isEqualTo("OK");
        policy.change(owner, member.accountId(), SiteGroup.VIEWER, "Fixture downgrade");
        assertThat(run(memberKey, "look").status().code()).isEqualTo("CREATOR_REQUIRED");
        jdbc.update("update workspaces set public_delivery=false where workspace_id=?", workspace.value());
        assertThat(run(key, "read street/paper").status().code()).isEqualTo("NOT_FOUND");
        auth.revokeApiKey(owner, workspace, key.subjectId());
        assertThat(run(key, "--help").status().code()).isEqualTo("CREATOR_REQUIRED");
    }

    @Test
    void aReadUnfoldsAStallAcrossSessionsWithoutMakingItASecretSearchFilter() {
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.POCKET));
        var before = (PlazaStreet.Street) run(key, "look").data();
        assertThat(before.stalls().getFirst().label()).isEqualTo("#???");
        assertThat(run(key, "stall " + before.stalls().getFirst().id()).status().code())
                .isEqualTo("OK");
        assertThat(((PublicPlazaReads.Page) run(key, "rumor needle").data()).total())
                .isEqualTo(1);
        run(key, "read street/paper");
        plaza = service();
        var after = (PlazaStreet.Street) run(key, "look").data();
        assertThat(after.stalls().getFirst().label()).isEqualTo("quiet");
        assertThat(after.well()).isEqualTo(before.well());
    }

    private AuthPrincipal member() {
        AuthPrincipal result = AccountFixtures.create(auth, "member", "Plaza-member-password-2026!");
        auth.acceptInvitation(
                result, auth.createInvitation(owner, workspace, Set.of()).token());
        policy.change(owner, result.accountId(), SiteGroup.CREATOR, "Fixture approval");
        return result;
    }

    @Test
    void candiesBelongToTheAccountAndAccumulateOncePerUtcDayWithDecorativeClientFlavors() throws Exception {
        plazaClock = Clock.fixed(Instant.parse("2026-10-09T23:59:30Z"), ZoneOffset.UTC);
        plaza = service();
        assertThat(run(key, "knock").status().code()).isEqualTo("OWNER_CONSENT_REQUIRED");
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.WISH, MachinePermission.POCKET));
        AuthPrincipal secondKey = key(owner);
        accounts.set(owner, secondKey.subjectId(), Set.of(MachinePermission.WISH, MachinePermission.POCKET));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> plaza.execute(key, workspace, "knock", "Claude"));
            var second = pool.submit(() -> plaza.execute(secondKey, workspace, "knock", "Codex"));
            assertThat(List.of(
                            first.get(10, TimeUnit.SECONDS).status().code(),
                            second.get(10, TimeUnit.SECONDS).status().code()))
                    .containsExactlyInAnyOrder("OK", "ALREADY_CLAIMED");
        }
        DefaultPlazaService.Pocket pocket = (DefaultPlazaService.Pocket)
                plaza.execute(key, workspace, "pocket", "Claude").data();
        assertThat(pocket.candy().balance()).isEqualTo(5);
        assertThat(pocket.candy().flavor()).isEqualTo("amber");
        assertThat(pocket.candy().nextClaimAt()).isEqualTo("2026-10-10T00:00:00Z");
        DefaultPlazaService.Pocket other = (DefaultPlazaService.Pocket)
                plaza.execute(secondKey, workspace, "pocket", "Codex").data();
        assertThat(other.candy().balance()).isEqualTo(5);
        assertThat(other.candy().flavor()).isEqualTo("mint");
        plazaClock = Clock.fixed(Instant.parse("2026-10-10T00:00:01Z"), ZoneOffset.UTC);
        plaza = service();
        assertThat(((PlazaWallet.Wallet) run(secondKey, "knock").data()).balance())
                .isEqualTo(10);
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.POCKET));
        assertThat(run(key, "knock").status().code()).isEqualTo("OWNER_CONSENT_REQUIRED");
    }

    private AuthPrincipal key(AuthPrincipal holder) {
        return auth.authenticateApiKey(
                auth.createApiKey(owner, workspace, holder.accountId(), Set.of(Capability.EXECUTE_REPOSITORY))
                        .token());
    }

    @Test
    void independentGrantChangesDoNotRestoreARevokedPocketOrLoseAnotherSelection() throws Exception {
        accounts.set(owner, key.subjectId(), Set.of(MachinePermission.POCKET));
        accounts.change(owner, key.subjectId(), MachinePermission.POCKET, false);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var candy = pool.submit(() -> accounts.change(owner, key.subjectId(), MachinePermission.WISH, true));
            var comment = pool.submit(() -> accounts.change(owner, key.subjectId(), MachinePermission.COMMENT, true));
            candy.get(10, TimeUnit.SECONDS);
            comment.get(10, TimeUnit.SECONDS);
        }
        Set<MachinePermission> permissions =
                accounts.withCreator(key, workspace, MachineAccounts.Identity::permissions);
        assertThat(permissions).containsExactlyInAnyOrder(MachinePermission.WISH, MachinePermission.COMMENT);
        assertThat(run(key, "pocket").status().code()).isEqualTo("OWNER_CONSENT_REQUIRED");
    }

    private PlazaResult run(AuthPrincipal actor, String command) {
        return plaza.execute(actor, workspace, command, "Fixture client");
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private PublicContentSnapshot value;

        @Override
        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("Exploration cannot fetch Git");
        }

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("Exploration cannot fetch Git");
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspace) {
            return value;
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> action) {
            return action.apply(value);
        }
    }
}
